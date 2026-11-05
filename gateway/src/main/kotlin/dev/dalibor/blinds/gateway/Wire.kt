package dev.dalibor.blinds.gateway

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The gateway's message format: a 17-byte big-endian header followed by a protobuf body.
 * Format taken from the MIT-licensed mobilus-client project (github.com/zpieslak/mobilus-client).
 */
object Category {
    const val LOGIN_REQUEST = 1
    const val LOGIN_RESPONSE = 2
    const val DEVICES_REQUEST = 3
    const val DEVICES_RESPONSE = 4
    const val CALL_EVENTS = 13
    const val STATE_REQUEST = 26
    const val STATE_RESPONSE = 27
}

class Frame(val category: Int, val timestamp: Int, val body: ByteArray)

object Wire {
    private const val HEADER_SIZE = 17
    private const val HEADER_LENGTH_FIELD = 13
    private const val PLATFORM_ANDROID = 4

    fun encode(frame: Frame, clientId: String): ByteArray {
        val id = clientId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(id.size == 6) { "client id must be 6 bytes" }
        return ByteBuffer.allocate(HEADER_SIZE + frame.body.size)
            .putInt(HEADER_LENGTH_FIELD)
            .put(frame.category.toByte())
            .putInt(frame.timestamp)
            .put(id)
            .put(PLATFORM_ANDROID.toByte())
            .put(0)
            .put(frame.body)
            .array()
    }

    /** Returns null for anything too short to hold a header. */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.getInt()
        val category = buffer.get().toInt() and 0xFF
        val timestamp = buffer.getInt()
        return Frame(category, timestamp, bytes.copyOfRange(HEADER_SIZE, bytes.size))
    }

    fun userKey(password: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))

    fun encrypt(key: ByteArray, timestamp: Int, plain: ByteArray) =
        cipher(Cipher.ENCRYPT_MODE, key, timestamp).doFinal(plain)

    fun decrypt(key: ByteArray, timestamp: Int, encrypted: ByteArray) =
        cipher(Cipher.DECRYPT_MODE, key, timestamp).doFinal(encrypted)

    private fun cipher(mode: Int, key: ByteArray, timestamp: Int): Cipher {
        val iv = ByteBuffer.allocate(16).putInt(12, timestamp).array()
        return Cipher.getInstance("AES/CFB/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }
}

/** Just enough protobuf for the gateway's messages, which saves a code generator and a runtime. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply {
        rawVarint((field shl 3).toLong())
        rawVarint(value)
    }

    fun bytes(field: Int, value: ByteArray) = apply {
        rawVarint(((field shl 3) or 2).toLong())
        rawVarint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun rawVarint(value: Long) {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            out.write(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
    }
}

/** Calls [onField] with each field as a Long (varint) or ByteArray (length-delimited). */
fun readProto(bytes: ByteArray, onField: (field: Int, value: Any) -> Unit) {
    val buffer = ByteBuffer.wrap(bytes)
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (shift >= 64) throw ProtoException()
            val b = buffer.get().toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
    try {
        while (buffer.hasRemaining()) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            if (field <= 0) throw ProtoException()
            when ((tag and 7).toInt()) {
                0 -> onField(field, varint())
                1 -> buffer.position(buffer.position() + 8)
                2 -> {
                    val size = varint()
                    if (size < 0 || size > buffer.remaining()) throw ProtoException()
                    val value = ByteArray(size.toInt())
                    buffer.get(value)
                    onField(field, value)
                }
                5 -> buffer.position(buffer.position() + 4)
                else -> throw ProtoException()
            }
        }
    } catch (e: RuntimeException) {
        // Underflow and bad positions: the bytes aren't this message, usually a wrong key.
        throw ProtoException()
    }
}

class ProtoException : Exception("not a valid protobuf message")

object Messages {
    fun loginRequest(login: String, userKey: ByteArray) =
        ProtoWriter().string(1, login).bytes(2, userKey).toByteArray()

    fun callEvent(deviceId: Long, value: String): ByteArray {
        val event = ProtoWriter()
            .varint(2, deviceId)
            .varint(3, EVENT_NUMBER_COMMAND)
            .string(4, value)
            .toByteArray()
        return ProtoWriter().bytes(1, event).toByteArray()
    }

    fun loginResponse(body: ByteArray): LoginResult {
        var status = 0L
        var privateKey: ByteArray? = null
        var publicKey: ByteArray? = null
        readProto(body) { field, value ->
            when (field) {
                1 -> status = value as Long
                2 -> privateKey = value as ByteArray
                3 -> publicKey = value as ByteArray
            }
        }
        return LoginResult(status.toInt(), privateKey, publicKey)
    }

    fun devices(body: ByteArray): List<Device> {
        val devices = mutableListOf<Device>()
        readProto(body) { field, value ->
            if (field == 1) devices += device(value as ByteArray)
        }
        return devices
    }

    private fun device(body: ByteArray): Device {
        var id = 0L
        var name = ""
        var type = 0
        var favourite = false
        readProto(body) { field, value ->
            when (field) {
                1 -> id = value as Long
                2 -> name = String(value as ByteArray, Charsets.UTF_8)
                3 -> type = (value as Long).toInt()
                6 -> favourite = value as Long != 0L
            }
        }
        return Device(id, name, type, favourite)
    }

    /** Used for both current-state responses and call-event echoes, which share a layout. */
    fun events(body: ByteArray): List<DeviceEvent> {
        val events = mutableListOf<DeviceEvent>()
        readProto(body) { field, value ->
            if (field == 1) events += event(value as ByteArray)
        }
        return events
    }

    private fun event(body: ByteArray): DeviceEvent {
        var deviceId = 0L
        var value = ""
        readProto(body) { field, fieldValue ->
            when (field) {
                2 -> deviceId = fieldValue as Long
                4 -> value = String(fieldValue as ByteArray, Charsets.UTF_8)
            }
        }
        return DeviceEvent(deviceId, value)
    }

    private const val EVENT_NUMBER_COMMAND = 6L
}

class LoginResult(val status: Int, val privateKey: ByteArray?, val publicKey: ByteArray?)
class DeviceEvent(val deviceId: Long, val value: String)

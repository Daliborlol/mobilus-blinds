package dev.dalibor.blinds.gateway

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(cause: Throwable) : GatewayException("Can't reach the gateway", cause)
    class BadCredentials : GatewayException("Wrong login or password")
    class NoResponse : GatewayException("The gateway didn't respond")
}

/**
 * One blocking, authenticated connection to the gateway's MQTT broker. Sessions are short-lived:
 * open one, do the work, close it. Nothing stays connected in the background, so the app doesn't
 * cost battery while idle.
 */
class GatewaySession private constructor(
    private val mqtt: MqttClient,
    private val clientId: String,
    private val timeoutMs: Long,
) : AutoCloseable {

    private val incoming = LinkedBlockingQueue<Frame>()
    private var privateKey = ByteArray(0)
    private var publicKey = ByteArray(0)

    private fun login(login: String, userKey: ByteArray) {
        mqtt.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) = Unit
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            override fun messageArrived(topic: String, message: MqttMessage) {
                Wire.decode(message.payload)?.let(incoming::put)
            }
        })
        mqtt.subscribe(arrayOf(clientId, SHARED_TOPIC), intArrayOf(0, 0))
        publish(Category.LOGIN_REQUEST, Messages.loginRequest(login, userKey))

        val deadline = deadline()
        while (true) {
            val frame = next(deadline) ?: throw GatewayException.NoResponse()
            if (frame.category != Category.LOGIN_RESPONSE) continue
            val result = try {
                Messages.loginResponse(Wire.decrypt(userKey, frame.timestamp, frame.body))
            } catch (e: ProtoException) {
                // The gateway encrypts this with the password hash it knows, so unreadable means
                // the hash differs.
                throw GatewayException.BadCredentials()
            }
            val private = result.privateKey
            val public = result.publicKey
            if (result.status != 0 || !isAesKey(private) || !isAesKey(public)) {
                throw GatewayException.BadCredentials()
            }
            privateKey = private!!
            publicKey = public!!
            return
        }
    }

    fun devices(): List<Device> {
        publish(Category.DEVICES_REQUEST, ByteArray(0))
        return await(Category.DEVICES_RESPONSE, Messages::devices)
    }

    /** Device id to reported state value. The latest event for a device wins. */
    fun state(): Map<Long, String> {
        publish(Category.STATE_REQUEST, ByteArray(0))
        return await(Category.STATE_RESPONSE, Messages::events).associate { it.deviceId to it.value }
    }

    /** Sends the command to every device and waits until the gateway has echoed each of them. */
    fun send(deviceIds: Collection<Long>, command: Command) {
        val pending = deviceIds.toMutableSet()
        for (id in deviceIds) {
            val timestamp = now()
            val body = Wire.encrypt(privateKey, timestamp, Messages.callEvent(id, command.value))
            publish(Category.CALL_EVENTS, body, timestamp)
        }
        val deadline = deadline()
        while (pending.isNotEmpty()) {
            val frame = next(deadline) ?: throw GatewayException.NoResponse()
            if (frame.category != Category.CALL_EVENTS) continue
            try {
                Messages.events(Wire.decrypt(publicKey, frame.timestamp, frame.body))
                    .forEach { pending -= it.deviceId }
            } catch (e: ProtoException) {
                continue
            }
        }
    }

    override fun close() {
        try {
            if (mqtt.isConnected) mqtt.disconnectForcibly(0, 500)
            mqtt.close(true)
        } catch (e: MqttException) {
            // Nothing useful to do when closing a connection that is already gone.
        }
    }

    private fun publish(category: Int, body: ByteArray, timestamp: Int = now()) {
        val payload = Wire.encode(Frame(category, timestamp, body), clientId)
        try {
            mqtt.publish(COMMAND_TOPIC, payload, 0, false)
        } catch (e: MqttException) {
            throw GatewayException.Unreachable(e)
        }
    }

    /** Waits for the first readable reply of this category, skipping any other traffic. */
    private fun <T> await(category: Int, parse: (ByteArray) -> T): T {
        val deadline = deadline()
        while (true) {
            val frame = next(deadline) ?: throw GatewayException.NoResponse()
            if (frame.category != category) continue
            try {
                return parse(Wire.decrypt(privateKey, frame.timestamp, frame.body))
            } catch (e: ProtoException) {
                continue
            }
        }
    }

    private fun isAesKey(key: ByteArray?) = key != null && key.size in setOf(16, 24, 32)

    private fun next(deadline: Long): Frame? =
        incoming.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)

    private fun deadline() = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    private fun now() = (System.currentTimeMillis() / 1000).toInt()

    companion object {
        private const val PORT = 8884
        private const val SHARED_TOPIC = "clients"
        private const val COMMAND_TOPIC = "module"

        /** Connects and logs in, then returns the ready session. Blocks the calling thread. */
        fun open(host: String, login: String, userKey: ByteArray, timeoutMs: Long, port: Int = PORT): GatewaySession {
            val bytes = ByteArray(6).also(SecureRandom()::nextBytes)
            val clientId = bytes.joinToString("") { "%02X".format(it) }
            // IPv6 literals need brackets in a URI.
            val authority = if (':' in host) "[$host]" else host
            val mqtt = try {
                MqttClient("ws://$authority:$port/mqtt", clientId, MemoryPersistence())
            } catch (e: MqttException) {
                throw GatewayException.Unreachable(e)
            } catch (e: IllegalArgumentException) {
                throw GatewayException.Unreachable(e)
            }
            val session = GatewaySession(mqtt, clientId, timeoutMs)
            try {
                val options = MqttConnectOptions().apply {
                    mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                    isCleanSession = true
                    isAutomaticReconnect = false
                    connectionTimeout = (timeoutMs / 1000).toInt().coerceAtLeast(1)
                    keepAliveInterval = 30
                }
                mqtt.setTimeToWait(timeoutMs)
                try {
                    mqtt.connect(options)
                } catch (e: MqttException) {
                    throw GatewayException.Unreachable(e)
                }
                try {
                    session.login(login, userKey)
                } catch (e: MqttException) {
                    throw GatewayException.Unreachable(e)
                }
                return session
            } catch (e: Exception) {
                session.close()
                throw e
            }
        }
    }
}

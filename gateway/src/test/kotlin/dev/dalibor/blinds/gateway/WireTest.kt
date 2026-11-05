package dev.dalibor.blinds.gateway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun userKeyIsSha256OfPassword() = assertArrayEquals(
        hex("2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b"),
        Wire.userKey("secret"),
    )

    /** Expected bytes come from `openssl enc -aes-256-cfb`, not from this code. */
    @Test fun encryptionMatchesOpenssl() {
        val key = Wire.userKey("secret")
        val plain = "hello gateway, this spans two blocks".toByteArray()
        val expected = hex("750b5d6618899e123622cd7a9f7427b41ebb8544efb6d6169f23773811f97861d1e70899")
        assertArrayEquals(expected, Wire.encrypt(key, 1700000000, plain))
        assertArrayEquals(plain, Wire.decrypt(key, 1700000000, expected))
    }

    @Test fun headerLayout() {
        val bytes = Wire.encode(Frame(Category.CALL_EVENTS, 0x01020304, byteArrayOf(9)), "A1B2C3D4E5F6")
        assertArrayEquals(hex("0000000d" + "0d" + "01020304" + "a1b2c3d4e5f6" + "04" + "00" + "09"), bytes)
    }

    @Test fun decodeReadsHeaderAndBody() {
        val frame = Wire.decode(hex("0000000d" + "1b" + "01020304" + "a1b2c3d4e5f6" + "04" + "00" + "0708"))!!
        assertEquals(Category.STATE_RESPONSE, frame.category)
        assertEquals(0x01020304, frame.timestamp)
        assertArrayEquals(byteArrayOf(7, 8), frame.body)
    }

    @Test fun decodeRejectsShortMessages() = assertNull(Wire.decode(ByteArray(16)))

    /** Field bytes worked out by hand from the .proto definitions. */
    @Test fun callEventEncoding() = assertArrayEquals(
        hex("0a09" + "1005" + "1806" + "2203" + "383525"),
        Messages.callEvent(5, "85%"),
    )

    @Test fun parsesDevicesAndSkipsUnknownFields() {
        val device = ProtoWriter().varint(1, 300).string(2, "Kuchyň").varint(3, 2).varint(5, 1234)
            .varint(6, 1).varint(7, 1).toByteArray()
        val parsed = Messages.devices(ProtoWriter().bytes(1, device).toByteArray()).single()
        assertEquals(Device(300, "Kuchyň", 2, true), parsed)
    }

    @Test(expected = ProtoException::class)
    fun garbageIsRejected() {
        Messages.loginResponse(byteArrayOf(0x0A, 0x7F, 0x01))
    }

    @Test fun readsReportedState() {
        assertEquals(85, openPercent("85%"))
        assertEquals(100, openPercent("UP"))
        assertEquals(0, openPercent("DOWN:60$"))
        assertNull(openPercent("STOP"))
        assertNull(openPercent(null))
    }
}

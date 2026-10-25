package online.whoisthis.plugin.microcontroller

import online.whoisthis.capture.Capability
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A JPEG header with a SOF0 declaring [w]×[h]; enough for [Jpeg.dimensions], not a decodable image. */
internal fun fakeJpeg(w: Int, h: Int, payload: Int = 16): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    // APP0 (JFIF) segment, length 16.
    out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
    out.write("JFIF".toByteArray()); out.write(ByteArray(10))
    // SOF0: length 17, precision 8, height, width, 3 components.
    out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08))
    out.write(byteArrayOf((h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3))
    out.write(ByteArray(9))
    out.write(ByteArray(payload) { it.toByte() })
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
}

class ProtocolTest {
    @Test
    fun statusKeepsOnlyContractCapabilities() {
        val s = BoardStatus.parse(
            """{"id":"xiao-ab12","model":"XIAO ESP32-S3 Sense","firmware":"1.0.0","protocol":1,
               "capabilities":["camera.still","camera.stream","battery","laser.beam"],"battery":87,"stream_port":81}""",
        )
        assertEquals("xiao-ab12", s.id)
        assertEquals(listOf(Capability.CAMERA_STILL, Capability.CAMERA_STREAM, Capability.BATTERY), s.capabilities)
        assertEquals(87, s.battery)
        assertEquals(81, s.streamPort)
        assertEquals(Protocol.CAPTURE_CONTINUOUS, s.capture)
        assertEquals(false, s.onTap)
    }

    @Test
    fun statusOfAFobSaysItCapturesOnTap() {
        val s = BoardStatus.parse(
            """{"id":"xiao-ab12","model":"WhoIsThis fob","firmware":"1.1.0","protocol":1,
               "capabilities":["camera.still","input.tap","input.long_press","battery"],"capture":"on_tap","battery":60}""",
        )
        assertEquals(true, s.onTap)
        assertEquals(listOf(Capability.CAMERA_STILL, Capability.INPUT_TAP, Capability.INPUT_LONG_PRESS, Capability.BATTERY), s.capabilities)
        assertEquals(Protocol.DEFAULT_STREAM_PORT, s.streamPort)
        val b = Board(host = "10.0.0.9", name = "10.0.0.9").withStatus(s)
        assertEquals(true, b.captureOnTap)
        assertEquals("WhoIsThis fob", b.name)
    }

    @Test
    fun statusWithoutBatteryOrPortUsesDefaults() {
        val s = BoardStatus.parse("""{"id":"x","capabilities":["camera.still"],"battery":null}""")
        assertNull(s.battery)
        assertEquals(Protocol.DEFAULT_STREAM_PORT, s.streamPort)
        assertEquals("microcontroller", s.model)
    }

    @Test
    fun eventsCarryCursorAndPayload() {
        val e = BoardEvents.parse("""{"next":7,"events":[{"seq":6,"name":"tap","detail":"1"},{"seq":7,"name":"battery","detail":"80"}]}""")
        assertEquals(7L, e.next)
        assertEquals(listOf(BoardEvent(6, "tap", "1"), BoardEvent(7, "battery", "80")), e.events)
        assertEquals(BoardEvents(0, emptyList()), BoardEvents.parse("""{"events":[]}"""))
    }

    @Test
    fun presentBodyIsJson() {
        val o = org.json.JSONObject(Protocol.presentBody("Probably Ana", show = true, speak = false))
        assertEquals("Probably Ana", o.getString("text"))
        assertEquals(true, o.getBoolean("show"))
        assertEquals(false, o.getBoolean("speak"))
    }

    @Test
    fun jpegDimensionsComeFromTheSofMarker() {
        assertEquals(1280 to 720, Jpeg.dimensions(fakeJpeg(1280, 720)))
        assertEquals(320 to 240, Jpeg.dimensions(fakeJpeg(320, 240)))
        assertNull(Jpeg.dimensions(byteArrayOf(1, 2, 3)))
        assertNull(Jpeg.dimensions("not a jpeg at all, just text".toByteArray()))
    }

    @Test
    fun mjpegReadsPartsWithContentLength() {
        val a = fakeJpeg(640, 480, payload = 100)
        val b = fakeJpeg(640, 480, payload = 7)
        val out = ByteArrayOutputStream()
        fun part(bytes: ByteArray) {
            out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nX-Timestamp: 1234\r\n\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
        }
        part(a); part(b)
        out.write("--frame--\r\n".toByteArray())
        val m = Mjpeg(ByteArrayInputStream(out.toByteArray()), "frame")
        val p1 = m.next()!!
        assertArrayEquals(a, p1.body)
        assertEquals("1234", p1.headers["x-timestamp"])
        assertArrayEquals(b, m.next()!!.body)
        assertNull(m.next())
    }

    @Test
    fun mjpegWithoutContentLengthEndsAtTheNextBoundary() {
        val a = "AAAA\r\n-not-a-boundary\r\nBBBB".toByteArray()
        val b = "second".toByteArray()
        val raw = "--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + a + "\r\n--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + b + "\r\n--b--\r\n".toByteArray()
        val m = Mjpeg(ByteArrayInputStream(raw), "--b")
        assertArrayEquals(a, m.next()!!.body)
        assertArrayEquals(b, m.next()!!.body)
        assertNull(m.next())
    }

    @Test
    fun boundaryIsTakenFromTheContentType() {
        assertEquals("frame", Mjpeg.boundary("multipart/x-mixed-replace; boundary=frame"))
        assertEquals("123456789000000000000987654321", Mjpeg.boundary("multipart/x-mixed-replace;boundary=\"123456789000000000000987654321\""))
        assertNull(Mjpeg.boundary("image/jpeg"))
        assertNull(Mjpeg.boundary(null))
    }

    @Test
    fun urlsHonourAnExplicitPortOnlyForControlCalls() {
        assertEquals("http://192.168.1.42:80/whoisthis", HttpBoard.url("192.168.1.42", null, "/whoisthis").toString())
        assertEquals("http://192.168.1.42:8080/whoisthis", HttpBoard.url("192.168.1.42:8080", null, "/whoisthis").toString())
        assertEquals("http://192.168.1.42:81/stream?max=1280", HttpBoard.url("192.168.1.42:8080", 81, "/stream?max=1280").toString())
        assertEquals("http://whoisthis-cam.local:80/capture", HttpBoard.url(" http://whoisthis-cam.local/ ", null, "/capture").toString())
    }
}

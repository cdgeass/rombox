package com.cdgeass.rombox.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MultipartStreamTest {

    private val boundary = "------------------------EXBkHsNLk8ET0HOHLxkaL4"

    @Test
    fun parsesSingleFilePart() {
        val body = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"tiny.gba\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "hello" +
            "\r\n--$boundary--\r\n"

        val parser = MultipartStream(ByteArrayInputStream(body.toByteArray()), boundary)
        assertTrue(parser.begin())
        val part = parser.nextPart()
        assertEquals("file", part?.fieldName)
        assertEquals("tiny.gba", part?.fileName)
        val out = ByteArrayOutputStream()
        val written = parser.writeBodyTo(out)
        assertEquals(5L, written)
        assertEquals("hello", out.toString(Charsets.UTF_8))
        assertNull(parser.nextPart())
    }

    @Test
    fun parsesBinaryBodyWithCrLf() {
        val payload = ByteArray(1000) { (it % 256).toByte() }
        val body = "--$boundary\r\n".toByteArray() +
            "Content-Disposition: form-data; name=\"file\"; filename=\"bin.gba\"\r\n\r\n".toByteArray() +
            payload +
            "\r\n--$boundary--\r\n".toByteArray()

        val parser = MultipartStream(ByteArrayInputStream(body), boundary)
        assertTrue(parser.begin())
        val part = parser.nextPart()
        assertEquals("bin.gba", part?.fileName)
        val out = ByteArrayOutputStream()
        assertEquals(1000L, parser.writeBodyTo(out))
        assertTrue(payload.contentEquals(out.toByteArray()))
        assertNull(parser.nextPart())
    }
}

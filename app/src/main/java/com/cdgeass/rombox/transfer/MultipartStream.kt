package com.cdgeass.rombox.transfer

import java.io.InputStream
import java.io.OutputStream
import kotlin.experimental.and

data class PartHeader(
    val fieldName: String?,
    val fileName: String?,
    val contentType: String?,
)

/**
 * 极简的 multipart/form-data 流式解析器（只用标准库）。
 *
 * 用法：
 * ```
 * val parser = MultipartStream(input, boundary)
 * if (parser.begin()) {
 *     while (true) {
 *         val part = parser.nextPart() ?: break
 *         // part.fileName 为空表示普通字段
 *         parser.writeBodyTo(out)          // out 传 null 表示丢弃
 *     }
 * }
 * ```
 * body 用 KMP 流式匹配分隔符，不把整个文件读进内存，也不重复扫描每一个字节。
 */
class MultipartStream(
    private val input: InputStream,
    boundary: String,
) {
    private val firstDelimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    private val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
    private val failFirst = buildFailure(firstDelimiter)
    private val failNext = buildFailure(delimiter)

    private var pending = ByteArray(128)
    private var pendingLen = 0
    private var active = false

    // 自己维护读缓冲：不能直接用 input.read(ByteArray) 再把剩余字节丢掉
    private val readBuffer = ByteArray(32 * 1024)
    private var readPos = 0
    private var readLimit = 0

    private fun readByte(): Int {
        if (readPos >= readLimit) {
            readLimit = input.read(readBuffer)
            readPos = 0
            if (readLimit <= 0) return -1
        }
        return readBuffer[readPos++].toInt() and 0xFF
    }

    /** 跳过 preamble，定位到第一个分隔符。false = 报文格式不对 */
    fun begin(): Boolean {
        active = copyUntil(firstDelimiter, failFirst, null)
        return active
    }

    /** 读取下一段的头部；返回 null 表示报文结束 */
    fun nextPart(): PartHeader? {
        if (!active) return null
        val a = readByte()
        if (a < 0) {
            active = false
            return null
        }
        if (a == '-'.code) {
            // 结束分隔符 "--"
            readByte()
            active = false
            return null
        }
        if (a == '\r'.code) readByte() // 吃掉 \n
        // 兼容以 \n 开头的情况
        return readPartHeaders()
    }

    /** 把当前段 body 写到 [out]，读到下一处分隔符为止 */
    fun writeBodyTo(out: OutputStream?): Long {
        val counter = CountingOutputStream(out)
        val found = copyUntil(delimiter, failNext, counter)
        if (!found) active = false
        return counter.count
    }

    // ---------------------------------------------------------------- 内部实现

    private fun readPartHeaders(): PartHeader {
        var fieldName: String? = null
        var fileName: String? = null
        var contentType: String? = null

        while (true) {
            val line = readHeaderLine() ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            when {
                lower.startsWith("content-disposition:") -> {
                    fieldName = paramValue(line, "name")
                    fileName = paramValue(line, "filename") ?: paramValue(line, "filename*")
                }

                lower.startsWith("content-type:") -> contentType = line.substringAfter(':').trim()
            }
        }
        return PartHeader(fieldName, fileName, contentType)
    }

    /** 头部行可能是 UTF-8 文件名，按 UTF-8 解，失败再退回 ISO-8859-1 */
    private fun readHeaderLine(): String? {
        val bytes = java.io.ByteArrayOutputStream(64)
        while (true) {
            val c = readByte()
            if (c < 0) return if (bytes.size() == 0) null else decodeLine(bytes.toByteArray())
            if (c == '\n'.code) {
                val raw = bytes.toByteArray()
                val trimmed = if (raw.isNotEmpty() && raw[raw.size - 1] == '\r'.code.toByte()) {
                    raw.copyOf(raw.size - 1)
                } else {
                    raw
                }
                return decodeLine(trimmed)
            }
            bytes.write(c)
            if (bytes.size() > 8192) return decodeLine(bytes.toByteArray())
        }
    }

    private fun decodeLine(raw: ByteArray): String {
        // 浏览器一般发 UTF-8；Windows 下的 curl 可能发 GBK；都不是就按 Latin-1 兜底
        val utf8 = String(raw, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        runCatching {
            val gbk = String(raw, java.nio.charset.Charset.forName("GBK"))
            if (!gbk.contains('\uFFFD')) return gbk
        }
        return String(raw, Charsets.ISO_8859_1)
    }

    private fun paramValue(line: String, key: String): String? {
        val segments = line.substringAfter(':').split(';')
        for (segment in segments.drop(1)) {
            val text = segment.trim()
            if (!text.regionMatches(0, "$key=", 0, key.length + 1, ignoreCase = true)) continue
            var value = text.substring(key.length + 1).trim()
            if (value.startsWith("\"")) {
                value = value.substring(1)
                val end = value.indexOf('"')
                if (end >= 0) value = value.substring(0, end)
            }
            return value
        }
        return null
    }

    private fun append(byte: Byte) {
        if (pendingLen == pending.size) {
            val bigger = ByteArray(pending.size * 2)
            System.arraycopy(pending, 0, bigger, 0, pendingLen)
            pending = bigger
        }
        pending[pendingLen++] = byte
    }

    /**
     * 流式拷贝直到 [target] 出现（并消费掉它）。
     * 用 KMP 保证 O(n)；每读一字节就把「不可能再构成匹配」的部分写出去。
     */
    private fun copyUntil(target: ByteArray, fail: IntArray, out: OutputStream?): Boolean {
        var matched = 0
        while (true) {
            val value = readByte()
            if (value < 0) return false
            append(value.toByte())

            while (matched > 0 && (target[matched].toInt() and 0xFF) != value) {
                matched = fail[matched - 1]
            }
            if ((target[matched].toInt() and 0xFF) == value) matched++

            if (matched == target.size) {
                val rest = pendingLen - target.size
                if (rest > 0) out?.write(pending, 0, rest)
                pendingLen = 0
                matched = 0
                return true
            }

            if (pendingLen > matched) {
                val emitCount = pendingLen - matched
                out?.write(pending, 0, emitCount)
                System.arraycopy(pending, emitCount, pending, 0, matched)
                pendingLen = matched
            }
        }
    }

    private fun buildFailure(pattern: ByteArray): IntArray {
        val fail = IntArray(pattern.size)
        var k = 0
        for (i in 1 until pattern.size) {
            while (k > 0 && pattern[i] != pattern[k]) k = fail[k - 1]
            if (pattern[i] == pattern[k]) k++
            fail[i] = k
        }
        return fail
    }

    private class CountingOutputStream(private val delegate: OutputStream?) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            count++
            delegate?.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
            delegate?.write(b, off, len)
        }

        override fun flush() {
            delegate?.flush()
        }
    }
}

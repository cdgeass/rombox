package com.cdgeass.rombox.transfer

import com.cdgeass.rombox.core.model.RomPlatform
import com.cdgeass.rombox.core.platform.Platforms
import com.cdgeass.rombox.core.rom.Naming
import com.cdgeass.rombox.core.rom.RomFile
import com.cdgeass.rombox.core.rom.RomRepository
import com.cdgeass.rombox.core.rom.ZipImporter
import com.cdgeass.rombox.core.settings.AppSettings
import com.cdgeass.rombox.core.util.Fmt
import com.cdgeass.rombox.core.util.NetUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream

data class LogEntry(val time: Long, val text: String, val error: Boolean = false)

data class ServerState(
    val running: Boolean = false,
    val port: Int = AppSettings.DEFAULT_PORT,
    val urls: List<String> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val receivedBytes: Long = 0,
    val receivedFiles: Int = 0,
    val activeRequests: Int = 0,
    val error: String? = null,
)

/**
 * 内置的无线传输服务：手机开一个 HTTP 端口，电脑浏览器就能上传/管理 ROM。
 *
 * 路由：
 *   GET  /                 网页（上传 + 列表 + 删除/改名）
 *   GET  /api/roms         当前 ROM 列表 JSON
 *   GET  /api/info         目录/空间信息 JSON
 *   GET  /file?name=       下载单个 ROM
 *   POST /upload           multipart 上传（支持 .zip 自动解包）
 *   POST /delete           name=
 *   POST /rename           name= 规范命名
 *   POST /rename-all       整目录规范命名
 */
class RomHttpServer(
    private val settings: AppSettings,
    private val repository: RomRepository,
    private val onDataChanged: () -> Unit,
) {

    private val _state = MutableStateFlow(ServerState(port = settings.port))
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val executor = Executors.newFixedThreadPool(6)
    @Volatile
    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean get() = serverSocket != null

    private val platform: RomPlatform
        get() = Platforms.byId(settings.platformId) ?: Platforms.default

    // ------------------------------------------------------------ 生命周期

    fun start(port: Int = settings.port): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            _state.update {
                it.copy(
                    running = true,
                    port = port,
                    urls = NetUtils.urls(port),
                    error = null,
                )
            }
            log("服务已启动，端口 $port")
            Thread({ acceptLoop(socket) }, "rombox-http").apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            _state.update { it.copy(running = false, error = message) }
            log("启动失败：$message", error = true)
            false
        }
    }

    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        runCatching { socket.close() }
        _state.update { it.copy(running = false, activeRequests = 0) }
        log("服务已停止")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            executor.execute {
                try {
                    handle(client)
                } catch (e: Throwable) {
                    log("处理请求出错：${e.message}", error = true)
                }
            }
        }
    }

    // ------------------------------------------------------------ 请求处理

    private fun handle(client: Socket) {
        _state.update { it.copy(activeRequests = it.activeRequests + 1) }
        try {
            client.soTimeout = 120_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(client.getOutputStream(), 32 * 1024)
            val request = readRequest(input)
            if (request == null) {
                writeText(output, 400, "text/plain; charset=utf-8", "bad request")
                return
            }
            route(request, input, output)
            output.flush()
        } catch (e: SocketException) {
            // 浏览器提前断开，忽略
        } catch (e: Throwable) {
            android.util.Log.e("RomBox", "request failed", e)
            log("请求异常：${e.message}", error = true)
        } finally {
            runCatching { client.close() }
            _state.update { it.copy(activeRequests = (it.activeRequests - 1).coerceAtLeast(0)) }
        }
    }

    private fun route(request: Request, input: InputStream, out: OutputStream) {
        when {
            request.method == "GET" && (request.path == "/" || request.path == "/index.html") -> {
                val platform = this.platform
                val items = repository.listRoms(platform, computeCrc = false)
                val html = Page.render(
                    items = items,
                    dirPath = repository.romDir().absolutePath,
                    platformName = platform.displayName,
                    freeSpace = repository.freeSpace(),
                    accept = platform.extensions.joinToString(","),
                )
                writeText(out, 200, "text/html; charset=utf-8", html)
            }

            request.method == "GET" && request.path == "/api/roms" -> writeText(out, 200, JSON, romsJson())

            request.method == "GET" && request.path == "/api/info" -> writeText(out, 200, JSON, infoJson())

            request.method == "GET" && request.path == "/file" -> serveFile(request, out)

            request.method == "POST" && request.path == "/upload" -> handleUpload(request, input, out)

            request.method == "POST" && request.path == "/delete" -> handleDelete(request, input, out)

            request.method == "POST" && request.path == "/rename" -> handleRename(request, input, out)

            request.method == "POST" && request.path == "/rename-all" -> {
                val platform = this.platform
                val count = repository.renameAllToCanonical(platform)
                if (count > 0) onDataChanged()
                log("规范命名 $count 个文件")
                respondJson(out, request, """{"ok":true,"renamed":$count}""")
            }

            request.path == "/favicon.ico" -> writeText(out, 404, "text/plain", "")

            else -> writeText(out, 404, "text/plain; charset=utf-8", "not found")
        }
    }

    // ------------------------------------------------------------ 上传

    private fun handleUpload(request: Request, input: InputStream, out: OutputStream) {
        val contentType = request.headers["content-type"]
        val boundary = contentType?.let { BOUNDARY_PATTERN.find(it)?.groupValues?.get(1) }
        if (boundary.isNullOrBlank()) {
            respondJson(out, request, """{"ok":false,"error":"缺少 multipart boundary"}""", 400)
            return
        }

        val dir = repository.ensureRomDir()
        val platform = this.platform
        val saved = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val parser = MultipartStream(input, boundary)
        if (!parser.begin()) {
            respondJson(out, request, """{"ok":false,"error":"multipart 报文格式异常"}""", 400)
            return
        }

        while (true) {
            val part = parser.nextPart() ?: break
            val rawName = part.fileName
            if (rawName.isNullOrBlank()) {
                parser.writeBodyTo(null)
                continue
            }
            val safeName = Naming.sanitize(rawName.substringAfterLast('/').substringAfterLast('\\'))
            val isZip = safeName.lowercase().endsWith(".zip")
            val partPlatform = Platforms.forFile(safeName)
            if (partPlatform == null && !isZip) {
                parser.writeBodyTo(null)
                failed += "$safeName（不支持的类型）"
                log("拒绝 $safeName：不支持的类型", error = true)
                continue
            }

            val temp = File(dir, ".upload-${UUID.randomUUID()}.part")
            val crc32 = CRC32()
            val size = try {
                FileOutputStream(temp).use { fos ->
                    val checked = CheckedOutputStream(fos, crc32)
                    val buffered = BufferedOutputStream(checked, 128 * 1024)
                    val bytes = parser.writeBodyTo(buffered)
                    buffered.flush()
                    bytes
                }
            } catch (e: Exception) {
                runCatching { temp.delete() }
                failed += "$safeName（写入失败：${e.message}）"
                log("写入失败 $safeName：${e.message}", error = true)
                continue
            }

            if (isZip) {
                val imported = try {
                    ZipImporter.extract(temp, dir, Platforms.all)
                } catch (e: Exception) {
                    failed += "$safeName（解压失败：${e.message}）"
                    emptyList()
                }
                runCatching { temp.delete() }
                if (imported.isEmpty()) {
                    if (failed.none { it.startsWith(safeName) }) failed += "$safeName（包内没有 ROM）"
                    log("$safeName 里没有可导入的 ROM", error = true)
                } else {
                    saved += imported
                    log("解压 ${safeName} → ${imported.size} 个 ROM")
                }
            } else {
                val target = repository.uniqueTarget(dir, safeName)
                if (runCatching { temp.renameTo(target) }.getOrDefault(false)) {
                    saved += target.name
                    log("上传 ${target.name}（${Fmt.bytes(size)}）")
                } else {
                    runCatching { temp.delete() }
                    failed += "$safeName（保存失败）"
                    log("保存失败 $safeName", error = true)
                }
            }
            _state.update { it.copy(receivedBytes = it.receivedBytes + size, receivedFiles = it.receivedFiles + 1) }
            if (saved.isNotEmpty()) onDataChanged()
        }

        if (saved.isNotEmpty()) onDataChanged()
        val json = """{"ok":${failed.isEmpty()},"saved":${jsonArray(saved)},"failed":${jsonArray(failed)}}"""
        respondJson(out, request, json)
    }

    // ------------------------------------------------------------ 删除 / 改名 / 下载

    private fun handleDelete(request: Request, input: InputStream, out: OutputStream) {
        val name = formValue(readFormBody(request, input), "name")
        val file = resolveFile(name)
        if (file == null) {
            respondJson(out, request, """{"ok":false,"error":"文件不存在"}""", 404)
            return
        }
        val ok = repository.delete(file)
        if (ok) {
            log("删除 ${file.name}")
            onDataChanged()
        } else {
            log("删除失败 ${file.name}", error = true)
        }
        respondJson(out, request, """{"ok":$ok}""")
    }

    private fun handleRename(request: Request, input: InputStream, out: OutputStream) {
        val name = formValue(readFormBody(request, input), "name")
        val file = resolveFile(name)
        if (file == null) {
            respondJson(out, request, """{"ok":false,"error":"文件不存在"}""", 404)
            return
        }
        val header = repository.readHeader(file, Platforms.forFile(file.name) ?: platform)
        val target = repository.renameToCanonical(file, header)
        if (target != null) {
            log("规范命名 ${file.name} → ${target.name}")
            onDataChanged()
        }
        respondJson(out, request, """{"ok":${target != null},"name":${jsonString(target?.name ?: file.name)}}""")
    }

    private fun serveFile(request: Request, out: OutputStream) {
        val file = resolveFile(request.query["name"])
        if (file == null) {
            writeText(out, 404, "text/plain; charset=utf-8", "not found")
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ${file.length()}\r\n")
            append("Content-Disposition: attachment; filename*=UTF-8''${urlEncode(file.name)}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        file.inputStream().use { ins -> ins.copyTo(out, 128 * 1024) }
        out.flush()
        log("下载 ${file.name}")
    }

    private fun resolveFile(name: String?): File? {
        if (name.isNullOrBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val dir = repository.romDir().canonicalFile
        val file = File(dir, name).canonicalFile
        if (!file.path.startsWith(dir.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    // ------------------------------------------------------------ 工具

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val queryIndex = target.indexOf('?')
        val path = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val query = if (queryIndex >= 0) parseQuery(target.substring(queryIndex + 1)) else emptyMap()

        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val index = header.indexOf(':')
            if (index > 0) headers[header.substring(0, index).trim().lowercase()] = header.substring(index + 1).trim()
        }
        return Request(method, path, query, headers)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (builder.isEmpty()) null else builder.toString()
            if (c == '\n'.code) {
                if (builder.isNotEmpty() && builder.last() == '\r') builder.setLength(builder.length - 1)
                return builder.toString()
            }
            builder.append(c.toChar())
            if (builder.length > 16384) return builder.toString()
        }
    }

    private fun readFormBody(request: Request, input: InputStream): String {
        val length = request.headers["content-length"]?.toLongOrNull() ?: return ""
        if (length <= 0) return ""
        val size = minOf(length, 256L * 1024).toInt()
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n <= 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun parseQuery(raw: String): Map<String, String> = buildMap {
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val index = pair.indexOf('=')
            val key = if (index >= 0) pair.substring(0, index) else pair
            val value = if (index >= 0) pair.substring(index + 1) else ""
            put(decode(key), decode(value))
        }
    }

    private fun formValue(body: String, key: String): String? = parseQuery(body)[key]

    private fun decode(raw: String): String = runCatching {
        URLDecoder.decode(raw, "UTF-8")
    }.getOrDefault(raw)

    private fun respondJson(out: OutputStream, request: Request, body: String, code: Int = 200) {
        val wantsJson = request.headers["x-requested-with"] != null ||
            request.headers["accept"]?.contains("application/json") == true
        if (!wantsJson && code == 200) {
            val headers = buildString {
                append("HTTP/1.1 303 See Other\r\nLocation: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            }
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return
        }
        writeText(out, code, JSON, body)
    }

    private fun writeText(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${statusText(code)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        303 -> "See Other"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }

    private fun romsJson(): String {
        val platform = this.platform
        val items = repository.listRoms(platform, computeCrc = false)
        val body = items.joinToString(",") { romJson(it) }
        return """{"dir":${jsonString(repository.romDir().absolutePath)},"free":${repository.freeSpace()},"roms":[$body]}"""
    }

    private fun romJson(rom: RomFile): String = buildString {
        append("{\"name\":").append(jsonString(rom.name))
        append(",\"size\":").append(rom.size)
        append(",\"title\":").append(jsonString(rom.title))
        append(",\"code\":").append(jsonString(rom.gameCode ?: ""))
        append(",\"region\":").append(jsonString(rom.region ?: ""))
        append(",\"valid\":").append(rom.header?.valid ?: false)
        append("}")
    }

    private fun infoJson(): String = buildString {
        append("{\"dir\":").append(jsonString(repository.romDir().absolutePath))
        append(",\"free\":").append(repository.freeSpace())
        append(",\"total\":").append(repository.totalSpace())
        append(",\"running\":").append(isRunning)
        append(",\"port\":").append(_state.value.port)
        append(",\"receivedBytes\":").append(_state.value.receivedBytes)
        append(",\"receivedFiles\":").append(_state.value.receivedFiles)
        append(",\"platform\":").append(jsonString(platform.displayName))
        append("}")
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { jsonString(it) }

    private fun jsonString(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch.code < 0x20) builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun log(text: String, error: Boolean = false) {
        if (error) android.util.Log.w("RomBox", text) else android.util.Log.d("RomBox", text)
        _state.update { current ->
            val entry = LogEntry(System.currentTimeMillis(), text, error)
            val logs = (current.logs + entry).takeLast(MAX_LOGS)
            current.copy(logs = logs)
        }
    }

    /** 关闭服务并释放线程池 */
    fun shutdown() {
        stop()
        runCatching { executor.shutdownNow() }
    }

    companion object {
        private const val JSON = "application/json; charset=utf-8"
        private const val MAX_LOGS = 200
        private val BOUNDARY_PATTERN = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
    }
}

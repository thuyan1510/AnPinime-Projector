package com.anpinime.projector

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lightweight local loopback proxy (127.0.0.1) for Nguồn phim 1 (NguonC / StreamC) & Film4K streams.
 * Reuses HTTP/2 + TLS connection pool via OkHttp, injects hotlink headers, rewrites HLS playlists,
 * and unwraps PNG-disguised MPEG-TS segments smoothly without head-of-line stalls.
 */
class FilmMediaRelay(
    private val baseHeaders: Map<String, String>,
    private val userAgent: String = CatalogService.USER_AGENT
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "FilmMediaRelay").apply { isDaemon = true }
    }
    private val clients = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<Socket, Boolean>()
    )
    private val activeCalls = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<okhttp3.Call, Boolean>()
    )
    @Volatile private var running = false
    private var port = 0

    fun start(masterUrl: String, isHlsHint: Boolean = false): String {
        check(!running && !executor.isShutdown) { "Relay already used" }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running = true
        executor.execute {
            while (running) {
                try {
                    val client = socket.accept()
                    clients.add(client)
                    try {
                        executor.execute {
                            try { handleClient(client) } finally { clients.remove(client) }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        client.close()
                        clients.remove(client)
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }
        return proxyUrlFor(masterUrl, forcePlaylistExt = isHlsHint)
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        activeCalls.forEach { try { it.cancel() } catch (_: Exception) {} }
        activeCalls.clear()
        executor.shutdownNow()
    }

    private fun proxyUrlFor(remoteUrl: String, forcePlaylistExt: Boolean = false): String {
        val encoded = URLEncoder.encode(remoteUrl, "UTF-8")
        val clean = remoteUrl.substringBefore('?').lowercase()
        val ext = when {
            forcePlaylistExt || clean.endsWith(".m3u8") || clean.contains("/stream/hls") -> ".m3u8"
            clean.endsWith(".vtt") -> ".vtt"
            clean.endsWith(".srt") -> ".srt"
            clean.endsWith(".key") -> ".key"
            clean.endsWith(".ts") || clean.endsWith(".png") || clean.endsWith(".jpg") ||
                clean.endsWith(".jpeg") || clean.endsWith(".webp") || clean.endsWith(".f4k") -> ".ts"
            else -> ""
        }
        return "http://127.0.0.1:$port/proxy$ext?u=$encoded"
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 25000
            sock.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val pathAndQuery = parts[1]
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }
            val qIndex = pathAndQuery.indexOf("?u=")
            if (qIndex < 0) return
            val targetUrl = URLDecoder.decode(pathAndQuery.substring(qIndex + 3), "UTF-8")
            val remote = URL(targetUrl)

            val reqBuilder = Request.Builder().url(targetUrl)
            for ((k, v) in baseHeaders) {
                if (k.startsWith("X-StreamC-", ignoreCase = true)) continue
                reqBuilder.header(k, v)
            }
            if (baseHeaders.keys.none { it.equals("User-Agent", true) }) {
                reqBuilder.header("User-Agent", userAgent)
            }
            val cleanTarget = targetUrl.substringBefore('?').lowercase()
            val mightBePlaylist = pathAndQuery.substringBefore('?').endsWith(".m3u8", true) ||
                cleanTarget.endsWith(".m3u8") || cleanTarget.contains("/stream/hls")
            if (rangeHeader != null && !mightBePlaylist && !cleanTarget.endsWith(".png")) {
                reqBuilder.header("Range", rangeHeader)
            }

            val call = sharedHttp.newCall(reqBuilder.build())
            activeCalls.add(call)
            try {
                if (!running) return
                call.execute().use { resp ->
                    val out = sock.getOutputStream()
                    val code = resp.code
                    if (code >= 400) {
                        val errHeader = "HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(errHeader.toByteArray(Charsets.UTF_8))
                        out.flush()
                        return
                    }
                    val body = resp.body ?: return
                    val contentType = resp.header("Content-Type") ?: ""
                    val upstreamLength = body.contentLength()
                    val bis = BufferedInputStream(body.byteStream(), 32 * 1024)
                    val head = ByteArray(16)
                    val headLen = readFullyAtMost(bis, head)

                    val headUtf8 = if (headLen > 0) String(head, 0, headLen, Charsets.UTF_8).removePrefix("\uFEFF").trimStart() else ""
                    val isPlaylist = cleanTarget.endsWith(".m3u8") ||
                        cleanTarget.contains("/stream/hls") ||
                        contentType.contains("mpegurl", ignoreCase = true) ||
                        headUtf8.startsWith("#EXTM3U") ||
                        headUtf8.startsWith("#ENC-AESGCM")

                    if (isPlaylist) {
                        val baos = ByteArrayOutputStream()
                        if (headLen > 0) baos.write(head, 0, headLen)
                        bis.copyTo(baos, 32 * 1024)
                        val rawText = String(baos.toByteArray(), Charsets.UTF_8)
                        val text = StreamCResolver.unwrapEncryptedM3u8IfNeeded(
                            rawText,
                            baseHeaders["X-StreamC-Hash"]
                        )
                        val rewritten = rewritePlaylist(text, remote)
                        val bytes = rewritten.toByteArray(Charsets.UTF_8)
                        val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(bytes)
                        out.flush()
                        return
                    }

                    val hasPngSig = headLen >= PNG_SIG.size && PNG_SIG.indices.all { head[it] == PNG_SIG[it] }
                    if (hasPngSig) {
                        val baos = ByteArrayOutputStream(if (upstreamLength > headLen) upstreamLength.toInt() else 65536)
                        baos.write(head, 0, headLen)
                        bis.copyTo(baos, 32 * 1024)
                        val unwrapped = unwrapPng(baos.toByteArray())
                        val isTs = cleanTarget.endsWith(".ts") || cleanTarget.endsWith(".png") ||
                            contentType.contains("mp2t", ignoreCase = true) ||
                            (unwrapped.isNotEmpty() && unwrapped[0] == 0x47.toByte())
                        val mime = detectSegmentMime(cleanTarget, contentType, unwrapped, isTs)
                        writeBufferedResponse(out, unwrapped, mime, rangeHeader)
                        return
                    }

                    val isTs = cleanTarget.endsWith(".ts") || cleanTarget.endsWith(".png") ||
                        contentType.contains("mp2t", ignoreCase = true) ||
                        (headLen > 0 && head[0] == 0x47.toByte())
                    val isVtt = cleanTarget.endsWith(".vtt") || contentType.contains("text/vtt", true) ||
                        headUtf8.startsWith("WEBVTT")
                    val isSrt = cleanTarget.endsWith(".srt") || contentType.contains("subrip", true)
                    val mime = when {
                        isVtt -> "text/vtt"
                        isSrt -> "application/x-subrip"
                        cleanTarget.endsWith(".key") -> "application/octet-stream"
                        isTs -> "video/mp2t"
                        contentType.isNotBlank() && !contentType.startsWith("image/") && !contentType.startsWith("text/plain") -> contentType
                        else -> "video/mp4"
                    }

                    // If client requested a Range on a small un-PNG-wrapped asset and upstream returned 200 (ignored Range)
                    if (rangeHeader != null && code == 200) {
                        val baos = ByteArrayOutputStream(if (upstreamLength > headLen) upstreamLength.toInt() else 65536)
                        if (headLen > 0) baos.write(head, 0, headLen)
                        bis.copyTo(baos, 32 * 1024)
                        writeBufferedResponse(out, baos.toByteArray(), mime, rangeHeader)
                        return
                    }

                    // Fast zero-copy streaming tunnel directly to ExoPlayer
                    val sb = StringBuilder()
                    sb.append(if (code == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    sb.append("Content-Type: ").append(mime).append("\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    if (code == 206) {
                        resp.header("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
                    }
                    if (upstreamLength >= 0L) {
                        sb.append("Content-Length: ").append(upstreamLength).append("\r\n")
                    }
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    if (headLen > 0) out.write(head, 0, headLen)
                    val buf = ByteArray(32 * 1024)
                    while (running) {
                        val read = bis.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                    }
                    out.flush()
                }
            } catch (_: Throwable) {
            } finally {
                activeCalls.remove(call)
            }
        }
    }

    private fun readFullyAtMost(input: BufferedInputStream, dest: ByteArray): Int {
        var total = 0
        while (total < dest.size) {
            val n = input.read(dest, total, dest.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun detectSegmentMime(cleanTarget: String, contentType: String, data: ByteArray, isTs: Boolean): String {
        val isVtt = cleanTarget.endsWith(".vtt") || contentType.contains("text/vtt", true) ||
            (data.size >= 6 && String(data, 0, 6, Charsets.UTF_8) == "WEBVTT")
        val isSrt = cleanTarget.endsWith(".srt") || contentType.contains("subrip", true)
        return when {
            isVtt -> "text/vtt"
            isSrt -> "application/x-subrip"
            cleanTarget.endsWith(".key") -> "application/octet-stream"
            isTs -> "video/mp2t"
            contentType.isNotBlank() && !contentType.startsWith("image/") && !contentType.startsWith("text/plain") -> contentType
            else -> "video/mp4"
        }
    }

    private fun writeBufferedResponse(
        out: java.io.OutputStream,
        bytes: ByteArray,
        mime: String,
        rangeHeader: String?
    ) {
        val slice = parseRange(rangeHeader, bytes.size)
        if (slice != null) {
            val len = slice.last - slice.first + 1
            val header = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: $mime\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Range: bytes ${slice.first}-${slice.last}/${bytes.size}\r\n" +
                "Content-Length: $len\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes, slice.first, len)
        } else {
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
        }
        out.flush()
    }

    private fun rewritePlaylist(text: String, baseUrl: URL): String {
        val uriRegex = Regex("""URI="([^"]+)"""")
        var expectVariantPlaylist = false
        return text.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                expectVariantPlaylist = true
                line
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val isVariant = expectVariantPlaylist
                expectVariantPlaylist = false
                val resolved = URL(baseUrl, trimmed).toString()
                proxyUrlFor(resolved, forcePlaylistExt = isVariant)
            } else if (line.contains("URI=\"")) {
                val forceM3u8 = trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) &&
                    trimmed.contains("TYPE=AUDIO", ignoreCase = true)
                uriRegex.replace(line) { m ->
                    val raw = m.groupValues[1]
                    val resolved = URL(baseUrl, raw).toString()
                    "URI=\"${proxyUrlFor(resolved, forcePlaylistExt = forceM3u8)}\""
                }
            } else {
                line
            }
        }
    }

    companion object {
        private val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val PNG_SIG = byteArrayOf(
            137.toByte(), 80, 78, 71, 13, 10, 26, 10
        )

        fun unwrapPng(data: ByteArray): ByteArray {
            if (data.size <= 16) return data
            for (i in PNG_SIG.indices) {
                if (data[i] != PNG_SIG[i]) return data
            }
            var offset = 8
            var tsChunks: java.io.ByteArrayOutputStream? = null
            while (offset + 12 <= data.size) {
                val length = ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
                if (length < 0 || length > data.size - offset - 12) return data
                val end = offset + 12 + length
                if (data[offset + 4] == 't'.code.toByte() &&
                    data[offset + 5] == 's'.code.toByte() &&
                    data[offset + 6] == 'D'.code.toByte() &&
                    data[offset + 7] == 'a'.code.toByte()
                ) {
                    if (tsChunks == null) tsChunks = java.io.ByteArrayOutputStream(length)
                    tsChunks.write(data, offset + 8, length)
                } else if (data[offset + 4] == 'I'.code.toByte() &&
                    data[offset + 5] == 'E'.code.toByte() &&
                    data[offset + 6] == 'N'.code.toByte() &&
                    data[offset + 7] == 'D'.code.toByte()
                ) {
                    if (tsChunks != null && tsChunks.size() > 0) {
                        return tsChunks.toByteArray()
                    }
                    return if (end < data.size) data.copyOfRange(end, data.size) else data
                }
                offset = end
            }
            return if (tsChunks != null && tsChunks.size() > 0) tsChunks.toByteArray() else data
        }

        private fun parseRange(header: String?, total: Int): IntRange? {
            if (header == null || total <= 0 || !header.startsWith("bytes=")) return null
            val spec = header.removePrefix("bytes=").split("-", limit = 2)
            if (spec.size != 2) return null
            if (spec[0].isEmpty()) {
                val suffix = spec[1].toIntOrNull() ?: return null
                if (suffix <= 0) return null
                return (total - suffix).coerceAtLeast(0) until total
            }
            val start = spec[0].toIntOrNull() ?: return null
            if (start < 0 || start >= total) return null
            val end = if (spec[1].isEmpty()) total - 1 else (spec[1].toIntOrNull() ?: return null).coerceAtMost(total - 1)
            if (end < start) return null
            return start..end
        }
    }
}

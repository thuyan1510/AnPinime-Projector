package com.anpinime.projector

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Lightweight local loopback proxy (127.0.0.1) for Nguồn phim 1 (Film4k.net) streams.
 * Strips PNG header envelopes from HLS fMP4/TS segments and injects play tickets.
 */
class FilmMediaRelay(
    private val baseHeaders: Map<String, String>,
    private val userAgent: String = CatalogService.USER_AGENT
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(4)
    private val clients = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Socket, Boolean>()
    )
    private val connections = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>()
    )
    @Volatile private var running = false
    private var port = 0

    fun start(masterUrl: String): String {
        check(!running && !executor.isShutdown) { "Relay already used" }
        val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
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
        return proxyUrlFor(masterUrl)
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        connections.forEach { it.disconnect() }
        connections.clear()
        executor.shutdownNow()
    }

    private fun proxyUrlFor(remoteUrl: String): String {
        val encoded = URLEncoder.encode(remoteUrl, "UTF-8")
        val clean = remoteUrl.substringBefore('?').lowercase()
        val ext = if (clean.endsWith(".m3u8") || clean.contains("/stream/hls") || clean.contains("hls")) ".m3u8" else ""
        return "http://127.0.0.1:$port/proxy$ext?u=$encoded"
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15000
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
            var conn: HttpURLConnection? = null
            try {
                conn = (remote.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    for ((k, v) in baseHeaders) {
                        setRequestProperty(k, v)
                    }
                    if (baseHeaders.keys.none { it.equals("User-Agent", true) }) setRequestProperty("User-Agent", userAgent)
                }
                connections.add(conn)
                if (!running) return
                val rawBytes = conn.inputStream.use { it.readBytes() }
                val contentType = conn.contentType ?: ""
                val cleanTarget = targetUrl.substringBefore('?').lowercase()
                val isPlaylist = cleanTarget.endsWith(".m3u8") ||
                    cleanTarget.contains("/stream/hls") ||
                    contentType.contains("mpegurl", ignoreCase = true) ||
                    (rawBytes.size >= 7 && String(rawBytes, 0, 7, Charsets.UTF_8) == "#EXTM3U")

                val out = sock.getOutputStream()
                if (isPlaylist) {
                    val text = String(rawBytes, Charsets.UTF_8)
                    val rewritten = rewritePlaylist(text, remote)
                    val body = rewritten.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.UTF_8))
                    out.write(body)
                } else {
                    val unwrapped = unwrapPng(rawBytes)
                    val isTs = cleanTarget.endsWith(".ts") ||
                        contentType.contains("mp2t", ignoreCase = true) ||
                        (unwrapped.isNotEmpty() && unwrapped[0] == 0x47.toByte())
                    val mime = when {
                        cleanTarget.endsWith(".vtt") || contentType.contains("text/vtt") -> "text/vtt"
                        cleanTarget.endsWith(".key") -> "application/octet-stream"
                        isTs -> "video/mp2t"
                        contentType.isNotBlank() && !contentType.startsWith("image/") -> contentType
                        else -> "video/mp4"
                    }

                    val slice = parseRange(rangeHeader, unwrapped.size)
                    if (slice != null) {
                        val len = slice.last - slice.first + 1
                        val header = "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Type: $mime\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Content-Range: bytes ${slice.first}-${slice.last}/${unwrapped.size}\r\n" +
                            "Content-Length: $len\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(unwrapped, slice.first, len)
                    } else {
                        val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $mime\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Content-Length: ${unwrapped.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(unwrapped)
                    }
                }
                out.flush()
            } catch (_: Throwable) {
            } finally {
                conn?.disconnect()
                conn?.let { connections.remove(it) }
            }
        }
    }

    private fun rewritePlaylist(text: String, baseUrl: URL): String {
        val uriRegex = Regex("""URI="([^"]+)"""")
        return text.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val resolved = URL(baseUrl, trimmed).toString()
                proxyUrlFor(resolved)
            } else if (line.contains("URI=\"")) {
                uriRegex.replace(line) { m ->
                    val raw = m.groupValues[1]
                    val resolved = URL(baseUrl, raw).toString()
                    "URI=\"${proxyUrlFor(resolved)}\""
                }
            } else {
                line
            }
        }
    }

    companion object {
        private val PNG_SIG = byteArrayOf(
            137.toByte(), 80, 78, 71, 13, 10, 26, 10
        )

        fun unwrapPng(data: ByteArray): ByteArray {
            if (data.size <= 16) return data
            for (i in PNG_SIG.indices) {
                if (data[i] != PNG_SIG[i]) return data
            }
            var offset = 8
            while (offset + 12 <= data.size) {
                val length = ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
                if (length < 0 || length > data.size - offset - 12) return data
                val end = offset + 12 + length
                if (data[offset + 4] == 'I'.code.toByte() &&
                    data[offset + 5] == 'E'.code.toByte() &&
                    data[offset + 6] == 'N'.code.toByte() &&
                    data[offset + 7] == 'D'.code.toByte()
                ) {
                    return if (end < data.size) data.copyOfRange(end, data.size) else data
                }
                offset = end
            }
            return data
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

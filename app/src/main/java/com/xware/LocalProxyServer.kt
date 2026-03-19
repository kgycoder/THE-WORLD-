package com.xware

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 로컬 HTTP 프록시 서버.
 * MediaPlayer는 커스텀 헤더를 지원하지 않으므로,
 * localhost에 프록시를 띄워 OkHttp로 실제 URL에 헤더와 함께 요청을 전달.
 */
class LocalProxyServer {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var port = 0
    private var targetUrl = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun start(url: String): String {
        stop()
        targetUrl = url
        serverSocket = ServerSocket(0).also { port = it.localPort }
        executor.submit { acceptLoop() }
        android.util.Log.d("XWare/Proxy", "Started on port $port for $url")
        return "http://127.0.0.1:$port"
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (!ss.isClosed) {
            try {
                val socket = ss.accept()
                executor.submit { handleClient(socket) }
            } catch (e: Exception) {
                if (!ss.isClosed)
                    android.util.Log.e("XWare/Proxy", "accept error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val firstLine = reader.readLine() ?: return
                android.util.Log.d("XWare/Proxy", "Request: $firstLine")

                // HTTP Range 헤더 읽기 (MediaPlayer가 seek 시 Range 요청)
                var rangeHeader: String? = null
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith("Range:", ignoreCase = true))
                        rangeHeader = line.substringAfter(":").trim()
                    line = reader.readLine()
                }

                // OkHttp로 실제 URL에 요청
                val reqBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/124.0.0.0")
                    .header("Referer", "https://www.youtube.com/")
                    .header("Origin", "https://www.youtube.com")

                if (rangeHeader != null)
                    reqBuilder.header("Range", rangeHeader)

                val resp = client.newCall(reqBuilder.build()).execute()
                val statusCode = resp.code
                val contentType = resp.header("Content-Type", "audio/mpeg") ?: "audio/mpeg"
                val contentLength = resp.header("Content-Length", "-1") ?: "-1"
                val acceptRanges = resp.header("Accept-Ranges", "bytes") ?: "bytes"
                val contentRange = resp.header("Content-Range")

                // 응답 헤더 작성
                val sb = StringBuilder()
                sb.append("HTTP/1.1 $statusCode ${httpStatus(statusCode)}\r\n")
                sb.append("Content-Type: $contentType\r\n")
                sb.append("Accept-Ranges: $acceptRanges\r\n")
                if (contentLength != "-1")
                    sb.append("Content-Length: $contentLength\r\n")
                if (contentRange != null)
                    sb.append("Content-Range: $contentRange\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")

                val out = s.getOutputStream()
                out.write(sb.toString().toByteArray())

                // 바디 스트리밍
                resp.body?.byteStream()?.use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1)
                        out.write(buf, 0, n)
                }
                out.flush()
                resp.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("XWare/Proxy", "handleClient: ${e.message}")
        }
    }

    private fun httpStatus(code: Int) = when (code) {
        200 -> "OK"; 206 -> "Partial Content"
        301 -> "Moved Permanently"; 302 -> "Found"
        403 -> "Forbidden"; 404 -> "Not Found"
        else -> "Unknown"
    }
}

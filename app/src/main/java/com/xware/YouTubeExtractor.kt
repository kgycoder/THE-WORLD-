package com.xware

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class StreamInfo(
        val audioUrl: String,
        val title: String,
        val duration: Long,
        val thumbnailUrl: String
    )

    fun extractAudio(videoId: String): StreamInfo? {
        val videoUrl = "https://www.youtube.com/watch?v=$videoId"

        // 1순위: cobalt.tools
        tryCobalt(videoId, videoUrl)?.let { return it }

        // 2순위: Piped (접속 가능한 인스턴스)
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://piped-api.garudalinux.org",
            "https://api.piped.projectsegfau.lt",
            "https://pipedapi.adminforge.de"
        )
        for (inst in pipedInstances) {
            tryPiped(videoId, inst)?.let { return it }
        }

        android.util.Log.e("XWare/Extractor", "All sources failed for $videoId")
        return null
    }

    // ── cobalt.tools ─────────────────────────────────
    private fun tryCobalt(videoId: String, videoUrl: String): StreamInfo? {
        return try {
            val body = JSONObject().apply {
                put("url", videoUrl)
                put("aFormat", "best")
                put("isAudioOnly", true)
                put("disableMetadata", false)
            }.toString()

            val req = Request.Builder()
                .url("https://api.cobalt.tools/api/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "XWare/1.0")
                .build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: run { resp.close(); return null }
            resp.close()

            android.util.Log.d("XWare/Extractor", "cobalt response: $respBody")

            val doc    = JSONObject(respBody)
            val status = doc.optString("status", "")

            // status: "stream" or "redirect" → url 필드에 오디오 URL
            if (status == "stream" || status == "redirect" || status == "tunnel") {
                val url = doc.optString("url").takeIf { it.isNotEmpty() } ?: return null
                android.util.Log.d("XWare/Extractor", "cobalt OK: $url")
                return StreamInfo(
                    audioUrl     = url,
                    title        = "",
                    duration     = 0L,
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                )
            }

            android.util.Log.w("XWare/Extractor", "cobalt status=$status text=${doc.optString("text")}")
            null

        } catch (e: Exception) {
            android.util.Log.w("XWare/Extractor", "cobalt exception: ${e.message}")
            null
        }
    }

    // ── Piped ─────────────────────────────────────────
    private fun tryPiped(videoId: String, base: String): StreamInfo? {
        return try {
            val resp = client.newCall(
                Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", "XWare/1.0")
                    .header("Accept", "application/json")
                    .build()
            ).execute()

            if (!resp.isSuccessful) { resp.close(); return null }
            val body = resp.body?.string() ?: run { resp.close(); return null }
            resp.close()

            val doc = JSONObject(body)
            if (doc.has("error") || doc.has("message")) return null

            val title     = doc.optString("title", "")
            val duration  = doc.optLong("duration", 0L)
            val thumbnail = doc.optString("thumbnailUrl",
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
            val streams   = doc.optJSONArray("audioStreams") ?: return null

            data class AS(val url: String, val bitrate: Int, val isOpus: Boolean)
            val list = mutableListOf<AS>()
            for (i in 0 until streams.length()) {
                val s   = streams.getJSONObject(i)
                val url = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                list.add(AS(url, s.optInt("bitrate", 0),
                    s.optString("mimeType").contains("opus")))
            }
            if (list.isEmpty()) return null

            val best = list.sortedWith(
                compareByDescending<AS> { if (it.isOpus) 1 else 0 }
                    .thenByDescending { it.bitrate }
            ).first()

            android.util.Log.d("XWare/Extractor", "Piped $base OK")
            StreamInfo(best.url, title, duration, thumbnail)

        } catch (e: Exception) {
            android.util.Log.w("XWare/Extractor", "Piped $base: ${e.message}")
            null
        }
    }
}

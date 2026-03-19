package com.xware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerService : Service() {

    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val extractor = YouTubeExtractor()
    private val proxy = LocalProxyServer()
    private var currentTitle = ""
    private var duration = 0.0
    private var tickJob: Job? = null

    var onStateChanged: ((String) -> Unit)? = null
    var onTimeUpdate: ((Double, Double) -> Unit)? = null
    var onError: ((Int) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onDebug: ((String) -> Unit)? = null   // ← 디버그 콜백

    companion object {
        const val ACTION_LOAD    = "com.xware.PLAYER_LOAD"
        const val ACTION_PLAY    = "com.xware.PLAYER_PLAY"
        const val ACTION_PAUSE   = "com.xware.PLAYER_PAUSE"
        const val ACTION_SEEK    = "com.xware.PLAYER_SEEK"
        const val ACTION_STOP    = "com.xware.PLAYER_STOP"
        const val ACTION_VOL     = "com.xware.PLAYER_VOL"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_SEEK_MS  = "seek_ms"
        const val EXTRA_VOLUME   = "volume"
        private const val CH_ID  = "xware_player"
        private const val NID    = 2001
        var instance: AudioPlayerService? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NID, buildNote())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD  -> intent.getStringExtra(EXTRA_VIDEO_ID)?.let { load(it) }
            ACTION_PLAY  -> doPlay()
            ACTION_PAUSE -> doPause()
            ACTION_SEEK  -> {
                val ms = intent.getLongExtra(EXTRA_SEEK_MS, 0L).toInt()
                runCatching { player?.seekTo(ms) }
            }
            ACTION_VOL   -> {
                val v = intent.getFloatExtra(EXTRA_VOLUME, 1f)
                runCatching { player?.setVolume(v, v) }
            }
            ACTION_STOP  -> {
                stopTick(); proxy.stop(); release()
                stopForeground(true); stopSelf()
            }
        }
        return START_STICKY
    }

    private fun debug(msg: String) {
        android.util.Log.d("XWare/Player", msg)
        onDebug?.invoke(msg)
    }

    private fun load(videoId: String) {
        onStateChanged?.invoke("buffering")
        debug("🔍 스트림 추출 시작: $videoId")

        scope.launch(Dispatchers.IO) {
            var info: YouTubeExtractor.StreamInfo? = null
            var extractError = ""
            try {
                info = extractor.extractAudio(videoId)
            } catch (e: Exception) {
                extractError = e.message ?: "알 수 없는 오류"
            }

            withContext(Dispatchers.Main) {
                if (info == null) {
                    debug("❌ 스트림 추출 실패: $extractError")
                    onError?.invoke(-1)
                    return@withContext
                }

                debug("✅ 스트림 URL 획득 (${info.audioUrl.take(60)}...)")
                currentTitle = info.title
                duration = info.duration.toDouble()
                release()

                // 로컬 프록시를 통해 재생 (헤더 자동 주입)
                val proxyUrl = try {
                    proxy.start(info.audioUrl)
                } catch (e: Exception) {
                    debug("⚠️ 프록시 시작 실패, 직접 URL 사용: ${e.message}")
                    info.audioUrl
                }

                debug("🎵 MediaPlayer 준비 중: $proxyUrl")

                player = MediaPlayer().also { mp ->
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    try {
                        mp.setDataSource(proxyUrl)
                    } catch (e: Exception) {
                        debug("❌ setDataSource 실패: ${e.message}")
                        onError?.invoke(-2)
                        return@withContext
                    }
                    mp.setOnPreparedListener { p ->
                        duration = if (p.duration > 0) p.duration / 1000.0 else info.duration.toDouble()
                        debug("▶️ 재생 시작! duration=${duration}s")
                        onReady?.invoke()
                        onStateChanged?.invoke("ready")
                        p.start()
                        onStateChanged?.invoke("playing")
                        startTick()
                        updateNotification()
                    }
                    mp.setOnCompletionListener {
                        debug("⏹ 재생 완료")
                        onStateChanged?.invoke("ended")
                        stopTick()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        debug("❌ MediaPlayer 오류: what=$what extra=$extra")
                        onError?.invoke(what)
                        stopTick()
                        true
                    }
                    try {
                        mp.prepareAsync()
                        debug("⏳ prepareAsync 호출됨")
                    } catch (e: Exception) {
                        debug("❌ prepareAsync 실패: ${e.message}")
                        onError?.invoke(-3)
                    }
                }
            }
        }
    }

    private fun doPlay()  {
        runCatching { player?.start(); onStateChanged?.invoke("playing"); startTick(); updateNotification() }
    }
    private fun doPause() {
        runCatching { player?.pause(); onStateChanged?.invoke("paused"); stopTick(); updateNotification() }
    }

    private fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun startTick() {
        stopTick()
        tickJob = scope.launch {
            while (isActive) {
                runCatching {
                    val p = player
                    if (p != null && p.isPlaying)
                        onTimeUpdate?.invoke(p.currentPosition / 1000.0, duration)
                }
                delay(250)
            }
        }
    }

    private fun stopTick() { tickJob?.cancel(); tickJob = null }

    fun getCurrentTime() = runCatching { (player?.currentPosition ?: 0) / 1000.0 }.getOrDefault(0.0)
    fun getDuration()    = duration
    fun isPlaying()      = runCatching { player?.isPlaying ?: false }.getOrDefault(false)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "X-WARE 플레이어",
                NotificationManager.IMPORTANCE_LOW).also { it.setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNote(): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("X-WARE")
            .setContentText(currentTitle.ifEmpty { "재생 준비 중..." })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()

    private fun updateNotification() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NID, buildNote())
        }
    }

    override fun onDestroy() {
        instance = null; stopTick(); scope.cancel(); proxy.stop(); release()
        super.onDestroy()
    }
}

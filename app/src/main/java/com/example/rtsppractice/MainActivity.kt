package com.example.rtsppractice // ← 원래 package 줄 유지

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

private const val RTSP_URL = "rtsp://10.0.2.2:18554/test"
private const val TAG = "RTSP"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RtspPlayerScreen(url = RTSP_URL)
        }
    }
}

/**
 * 연결이 끊기면(에러 또는 라이브 종료) 지수 백오프(1, 2, 4, 8, 16초)로 재연결한다.
 * 재연결이 예약될 때마다 onScheduled로 화면에 알린다.
 */
private class Reconnector(
    private val player: ExoPlayer,
    private val onScheduled: (delaySec: Long, attempt: Int) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var attempt = 0

    private val retryRunnable = Runnable {
        Log.d(TAG, "재연결 시도 #$attempt")
        player.stop()
        player.prepare()
    }

    fun schedule(reason: String) {
        handler.removeCallbacks(retryRunnable)
        attempt++
        val delayMs = 1000L shl (attempt - 1).coerceAtMost(4)
        Log.d(TAG, "[$reason] ${delayMs / 1000}초 후 재연결 예약 (시도 $attempt)")
        onScheduled(delayMs / 1000, attempt)
        handler.postDelayed(retryRunnable, delayMs)
    }

    fun onReady() {
        if (attempt > 0) Log.d(TAG, "연결 복구, 재시도 횟수 초기화")
        attempt = 0
    }

    fun cancel() {
        handler.removeCallbacks(retryRunnable)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun RtspPlayerScreen(url: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 화면에 보여줄 상태
    var isLoading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>("카메라에 연결하는 중...") }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setDebugLoggingEnabled(true)
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(mediaSource)
            playWhenReady = true
        }
    }
    val reconnector = remember {
        Reconnector(player) { delaySec, attempt ->
            isLoading = false
            message = "연결이 끊겼어요. ${delaySec}초 후 다시 연결합니다 (시도 $attempt)"
        }
    }

    DisposableEffect(lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        Log.d(TAG, "state = BUFFERING")
                        isLoading = true
                        if (message == null) message = "카메라에 연결하는 중..."
                    }
                    Player.STATE_READY -> {
                        Log.d(TAG, "state = READY")
                        isLoading = false
                        message = null
                        reconnector.onReady()
                    }
                    Player.STATE_ENDED -> {
                        Log.d(TAG, "state = ENDED")
                        reconnector.schedule("ENDED")
                    }
                    Player.STATE_IDLE -> Log.d(TAG, "state = IDLE")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "error = ${error.errorCodeName}")
                reconnector.schedule("ERROR")
            }
        }
        player.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    Log.d(TAG, "ON_START → prepare")
                    isLoading = true
                    message = "카메라에 연결하는 중..."
                    player.prepare()
                }
                Lifecycle.Event.ON_STOP -> {
                    Log.d(TAG, "ON_STOP → stop")
                    reconnector.cancel()
                    player.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            reconnector.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 영상 위에 상태 표시
        if (isLoading || message != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading) CircularProgressIndicator()
                message?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}
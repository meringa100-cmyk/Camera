package nl.bermering.beveiligingscamera

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

@UnstableApi
class MainActivity : ComponentActivity() {
    private var a: ExoPlayer? = null
    private var b: ExoPlayer? = null
    private lateinit var v1: PlayerView
    private lateinit var v2: PlayerView
    private lateinit var st: TextView
    private lateinit var st1: TextView
    private lateinit var st2: TextView
    private lateinit var box1: View
    private lateinit var box2: View
    private lateinit var header: View
    private lateinit var overallStatus: View
    private lateinit var reconnect: View
    private lateinit var cameraRow: View
    private val handler = Handler(Looper.getMainLooper())
    private var fullScreenCamera = 0

    private val u1 = "rtsp://admin:123456@192.168.2.26:554/cam/realmonitor?channel=0&subtype=1"
    private val u2 = "rtsp://admin:123456@192.168.2.27:554/cam/realmonitor?channel=0&subtype=1"

    override fun onCreate(x: Bundle?) {
        super.onCreate(x)
        setContentView(R.layout.activity_main)
        v1 = findViewById(R.id.player1); v2 = findViewById(R.id.player2)
        st = findViewById(R.id.status); st1 = findViewById(R.id.status1); st2 = findViewById(R.id.status2)
        box1 = findViewById(R.id.camera1Box); box2 = findViewById(R.id.camera2Box)
        header = findViewById(android.R.id.content).findViewById(R.id.appHeader)
        overallStatus = findViewById(R.id.status); reconnect = findViewById(R.id.reconnect)
        cameraRow = findViewById(R.id.cameraRow)
        reconnect.setOnClickListener { start() }
        v1.setOnClickListener { toggleFullscreen(1) }
        v2.setOnClickListener { toggleFullscreen(2) }
        start()
    }

    private fun make(uri: String, camera: Int): ExoPlayer {
        val p = ExoPlayer.Builder(this).build()
        val s = RtspMediaSource.Factory().setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(uri))
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val text = when (state) {
                    Player.STATE_READY -> "● LIVE"
                    Player.STATE_BUFFERING -> "● Verbinden…"
                    else -> "● Wachten…"
                }
                if (camera == 1) st1.text = text else st2.text = text
                updateOverall()
            }
            override fun onPlayerError(e: PlaybackException) {
                if (camera == 1) { st1.text = "● VERBINDING VERLOREN"; scheduleRetry(1) }
                else { st2.text = "● VERBINDING VERLOREN"; scheduleRetry(2) }
                updateOverall()
            }
        })
        p.setMediaSource(s); p.prepare(); p.playWhenReady = true
        return p
    }

    private fun scheduleRetry(camera: Int) {
        handler.postDelayed({
            if (camera == 1 && a != null) { a?.release(); a = make(u1, 1); v1.player = a }
            if (camera == 2 && b != null) { b?.release(); b = make(u2, 2); v2.player = b }
        }, 3000L)
    }

    private fun updateOverall() {
        val left = st1.text.toString(); val right = st2.text.toString()
        st.text = when {
            left.contains("LIVE") && right.contains("LIVE") -> "● BEIDE CAMERA'S LIVE"
            left.contains("LIVE") -> "● Achtertuin LIVE • Voorkant verbinden…"
            right.contains("LIVE") -> "● Voorkant LIVE • Achtertuin verbinden…"
            else -> "● Camera's verbinden…"
        }
    }

    private fun immersive(on: Boolean) {
        val controller = window.insetsController
        if (on) {
            controller?.hide(WindowInsets.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsets.Type.systemBars())
        }
    }

    private fun toggleFullscreen(camera: Int) {
        if (fullScreenCamera == camera) {
            fullScreenCamera = 0
            box1.visibility = View.VISIBLE; box2.visibility = View.VISIBLE
            header.visibility = View.VISIBLE; overallStatus.visibility = View.VISIBLE; reconnect.visibility = View.VISIBLE
            cameraRow.layoutParams.height = 0
            cameraRow.layoutParams = cameraRow.layoutParams.apply { height = 0 }
            (cameraRow.layoutParams as android.widget.LinearLayout.LayoutParams).weight = 1f
            immersive(false)
        } else {
            fullScreenCamera = camera
            box1.visibility = if (camera == 1) View.VISIBLE else View.GONE
            box2.visibility = if (camera == 2) View.VISIBLE else View.GONE
            header.visibility = View.GONE; overallStatus.visibility = View.GONE; reconnect.visibility = View.GONE
            cameraRow.layoutParams = cameraRow.layoutParams.apply { height = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
            (cameraRow.layoutParams as android.widget.LinearLayout.LayoutParams).weight = 0f
            immersive(true)
        }
    }

    private fun start() {
        handler.removeCallbacksAndMessages(null)
        a?.release(); b?.release()
        fullScreenCamera = 0
        box1.visibility = View.VISIBLE; box2.visibility = View.VISIBLE
        header.visibility = View.VISIBLE; overallStatus.visibility = View.VISIBLE; reconnect.visibility = View.VISIBLE
        a = make(u1, 1); b = make(u2, 2)
        v1.player = a; v2.player = b; updateOverall()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        a?.release(); b?.release(); a = null; b = null
    }
}

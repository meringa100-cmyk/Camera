package nl.bermering.beveiligingscamera

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var shot1: View
    private lateinit var shot2: View
    private lateinit var backFullscreen: View
    private lateinit var switchFullscreen: View    private val handler = Handler(Looper.getMainLooper())
    private var fullScreenCamera = 0

    private val u1 = "rtsp://admin:123456@192.168.2.26:554/cam/realmonitor?channel=0&subtype=1"
    private val u2 = "rtsp://admin:123456@192.168.2.27:554/cam/realmonitor?channel=0&subtype=1"

    override fun onCreate(x: Bundle?) {
        super.onCreate(x)
        setContentView(R.layout.activity_main)
        v1 = findViewById(R.id.player1)
        v2 = findViewById(R.id.player2)
        st = findViewById(R.id.status)
        st1 = findViewById(R.id.status1)
        st2 = findViewById(R.id.status2)
        box1 = findViewById(R.id.camera1Box)
        box2 = findViewById(R.id.camera2Box)
        header = findViewById(R.id.appHeader)
        overallStatus = findViewById(R.id.status)
        reconnect = findViewById(R.id.reconnect)
        cameraRow = findViewById(R.id.cameraRow)
        shot1 = findViewById(R.id.screenshot1)
        shot2 = findViewById(R.id.screenshot2)
        backFullscreen = findViewById(R.id.backFullscreen)
        switchFullscreen = findViewById(R.id.switchFullscreen)
        reconnect.setOnClickListener { start() }
        v1.setOnClickListener { toggleFullscreen(1) }
        v2.setOnClickListener { toggleFullscreen(2) }
        shot1.setOnClickListener { takeScreenshot(v1, "Achtertuin") }
        shot2.setOnClickListener { takeScreenshot(v2, "Voorkant") }
        backFullscreen.setOnClickListener { exitFullscreen() }
        switchFullscreen.setOnClickListener { if (fullScreenCamera == 1) toggleFullscreen(2) else if (fullScreenCamera == 2) toggleFullscreen(1) }
        start()
    }

    private fun make(uri: String, camera: Int): ExoPlayer {
        val p = ExoPlayer.Builder(this).build()
        val s = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(uri))

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val text = when (state) {
                    Player.STATE_READY -> "● LIVE"
                    Player.STATE_BUFFERING -> "● Verbinden…"
                    else -> "● Wachten…"
                }
                if (camera == 1) {
                    st1.text = text
                    st1.setTextColor(if (state == Player.STATE_READY) Color.GREEN else Color.WHITE)
                } else {
                    st2.text = text
                    st2.setTextColor(if (state == Player.STATE_READY) Color.GREEN else Color.WHITE)
                }
                updateOverall()
            }

            override fun onPlayerError(e: PlaybackException) {
                if (camera == 1) {
                    st1.text = "● VERBINDING VERLOREN"
                    st1.setTextColor(Color.RED)
                    scheduleRetry(1)
                } else {
                    st2.text = "● VERBINDING VERLOREN"
                    st2.setTextColor(Color.RED)
                    scheduleRetry(2)
                }
                updateOverall()
            }
        })
        p.setMediaSource(s)
        p.prepare()
        p.playWhenReady = true
        return p
    }

    private fun takeScreenshot(playerView: PlayerView, cameraName: String) {
        val surface = playerView.videoSurfaceView
        if (surface == null || !surface.isShown) {
            Toast.makeText(this, "Beeld nog niet beschikbaar", Toast.LENGTH_SHORT).show()
            return
        }

        if (surface is SurfaceView) {
            val bitmap = Bitmap.createBitmap(
                surface.width.coerceAtLeast(1),
                surface.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) saveBitmap(bitmap, cameraName)
                else {
                    bitmap.recycle()
                    Toast.makeText(this, "Foto maken mislukt", Toast.LENGTH_SHORT).show()
                }
            }, handler)
        } else {
            Toast.makeText(this, "Foto maken mislukt", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmap(bitmap: Bitmap, cameraName: String) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "360Eyes_" + cameraName + "_" + stamp + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/360Eyes")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            bitmap.recycle()
            Toast.makeText(this, "Foto opslaan mislukt", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Toast.makeText(this, "Foto opgeslagen in Foto's/360Eyes", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Foto opslaan mislukt", Toast.LENGTH_SHORT).show()
        } finally {
            bitmap.recycle()
        }
    }

    private fun scheduleRetry(camera: Int) {
        handler.postDelayed({
            if (camera == 1 && a != null) {
                a?.release()
                a = make(u1, 1)
                v1.player = a
            }
            if (camera == 2 && b != null) {
                b?.release()
                b = make(u2, 2)
                v2.player = b
            }
        }, 3000L)
    }

    private fun updateOverall() {
        val left = st1.text.toString()
        val right = st2.text.toString()
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
        } else controller?.show(WindowInsets.Type.systemBars())
    }

    private fun toggleFullscreen(camera: Int) {
        if (fullScreenCamera == camera) exitFullscreen() else {
            fullScreenCamera = camera
            box1.visibility = if (camera == 1) View.VISIBLE else View.GONE
            box2.visibility = if (camera == 2) View.VISIBLE else View.GONE
            header.visibility = View.GONE
            overallStatus.visibility = View.GONE
            reconnect.visibility = View.GONE
            shot1.visibility = if (camera == 1) View.VISIBLE else View.GONE
            shot2.visibility = if (camera == 2) View.VISIBLE else View.GONE
            backFullscreen.visibility = View.VISIBLE
            switchFullscreen.visibility = View.VISIBLE
                        val params = cameraRow.layoutParams as android.widget.LinearLayout.LayoutParams
            params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            params.weight = 0f
            cameraRow.layoutParams = params
            immersive(true)
        }
    }

    private fun exitFullscreen() {
        fullScreenCamera = 0
        box1.visibility = View.VISIBLE
        box2.visibility = View.VISIBLE
        header.visibility = View.VISIBLE
        overallStatus.visibility = View.VISIBLE
        reconnect.visibility = View.VISIBLE
        shot1.visibility = View.VISIBLE
        shot2.visibility = View.VISIBLE
        backFullscreen.visibility = View.GONE
        switchFullscreen.visibility = View.GONE
                val params = cameraRow.layoutParams as android.widget.LinearLayout.LayoutParams
        params.height = 0
        params.weight = 1f
        cameraRow.layoutParams = params
        immersive(false)
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onBackPressed() {
        if (fullScreenCamera != 0) exitFullscreen() else super.onBackPressed()
    }

    private fun start() {
        handler.removeCallbacksAndMessages(null)
        st1.setTextColor(Color.WHITE)
        st2.setTextColor(Color.WHITE)
        a?.release()
        b?.release()
        fullScreenCamera = 0
        immersive(false)
        box1.visibility = View.VISIBLE
        box2.visibility = View.VISIBLE
        header.visibility = View.VISIBLE
        overallStatus.visibility = View.VISIBLE
        reconnect.visibility = View.VISIBLE
        shot1.visibility = View.VISIBLE
        shot2.visibility = View.VISIBLE
        backFullscreen.visibility = View.GONE
        switchFullscreen.visibility = View.GONE
                val params = cameraRow.layoutParams as android.widget.LinearLayout.LayoutParams
        params.height = 0
        params.weight = 1f
        cameraRow.layoutParams = params
        a = make(u1, 1)
        b = make(u2, 2)
        v1.player = a
        v2.player = b
        updateOverall()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        a?.release()
        b?.release()
        a = null
        b = null
    }
}

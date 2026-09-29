package nl.bermering.beveiligingscamera

import android.os.Bundle
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

    // ONVIF is enabled on the cameras. Default ONVIF credentials shown by 360Eyes:
    // username admin, password 123456 after reset.
    private val u1 = "rtsp://admin:123456@192.168.2.26:554/cam/realmonitor?channel=0&subtype=1"
    private val u2 = "rtsp://admin:123456@192.168.2.27:554/cam/realmonitor?channel=0&subtype=1"

    override fun onCreate(x: Bundle?) {
        super.onCreate(x)
        setContentView(R.layout.activity_main)
        v1 = findViewById(R.id.player1)
        v2 = findViewById(R.id.player2)
        st = findViewById(R.id.status)
        findViewById<Button>(R.id.reconnect).setOnClickListener { start() }
        start()
    }

    private fun make(uri: String): ExoPlayer {
        val p = ExoPlayer.Builder(this).build()
        val s = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(uri))

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(x: Int) {
                st.text = when (x) {
                    Player.STATE_READY -> "● LIVE — camera verbonden"
                    Player.STATE_BUFFERING -> "● Verbinden met camera's…"
                    else -> "● Wachten op beeld"
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                st.text = "● RTSP-fout — controleer ONVIF/RTSP"
            }
        })

        p.setMediaSource(s)
        p.prepare()
        p.playWhenReady = true
        return p
    }

    private fun start() {
        a?.release()
        b?.release()
        a = make(u1)
        b = make(u2)
        v1.player = a
        v2.player = b
    }

    override fun onStop() {
        super.onStop()
        a?.release()
        b?.release()
    }
}

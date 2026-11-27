package com.lee.voicewave

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : Activity() {

    companion object {
        private const val SAMPLE_RATE = 16000
        // Mic loudness range mapped to the wave height.
        // Raise FLOOR_DB (e.g. -50) if the wave twitches in a quiet room;
        // lower CEIL_DB (e.g. -20) if normal talking doesn't fill the screen.
        private const val FLOOR_DB = -55.0
        private const val CEIL_DB = -15.0
        private const val REQ_MIC = 1
    }

    private lateinit var wave: WaveView
    private var recorder: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wave = WaveView(this)
        // Tap to switch between the voice wave and an oscilloscope trace
        wave.setOnClickListener {
            wave.mode = if (wave.mode == WaveView.Mode.WAVE) WaveView.Mode.SCOPE else WaveView.Mode.WAVE
        }
        setContentView(wave)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        startMic()
    }

    override fun onPause() {
        stopMic()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, 4096)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        recorder = rec
        running = true
        rec.startRecording()

        micThread = Thread {
            val buf = ShortArray(512) // ~32 ms per read
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(10); continue }
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
                val rms = sqrt(sum / n)
                val db = 20.0 * log10(rms / 32768.0 + 1e-9)
                wave.targetLevel = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
                wave.pushSamples(buf, n)
            }
        }.apply { name = "mic"; start() }
    }

    private fun stopMic() {
        running = false
        micThread?.join(500)
        micThread = null
        recorder?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        recorder = null
        wave.targetLevel = 0f
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startMic()
        }
    }
}

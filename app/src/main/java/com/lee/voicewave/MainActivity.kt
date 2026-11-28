package com.lee.voicewave

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : Activity() {

    companion object {
        // Mic loudness range mapped to the wave height.
        // Raise FLOOR_DB (e.g. -50) if the wave twitches in a quiet room;
        // lower CEIL_DB (e.g. -20) if normal talking doesn't fill the screen.
        private const val FLOOR_DB = -55.0
        private const val CEIL_DB = -15.0
        private const val REQ_MIC = 1

        // Tried in order until one delivers real (non-silent) audio.
        private val SOURCES = listOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMM",
        )
        // 44100 is the only rate Android guarantees on every device.
        private val RATES = listOf(44100, 48000, 22050, 16000, 8000)
        private const val PROBE_MS = 1500L
    }

    private lateinit var wave: WaveView
    private var micThread: Thread? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wave = WaveView(this)
        // Tap: switch wave / oscilloscope. Long-press: show or hide mic diagnostics.
        wave.setOnClickListener {
            wave.mode = if (wave.mode == WaveView.Mode.WAVE) WaveView.Mode.SCOPE else WaveView.Mode.WAVE
        }
        wave.setOnLongClickListener { wave.showStatus = !wave.showStatus; true }
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
    private fun open(source: Int, rate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        return try {
            val rec = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return null }
            rec
        } catch (e: Exception) { null }
    }

    private fun startMic() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wave.status = "Microphone permission not granted.\nAllow it in Settings → Apps → Voice Wave → Permissions."
            wave.showStatus = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        running = true
        micThread = Thread { micLoop() }.apply { name = "mic"; start() }
    }

    private fun micLoop() {
        val buf = ShortArray(1024)
        var rec: AudioRecord? = null
        var label = ""
        var fallback: Pair<AudioRecord, String>? = null
        val tried = StringBuilder()

        // Probe each source/rate until one gives non-zero samples.
        search@ for ((src, srcName) in SOURCES) {
            for (rate in RATES) {
                if (!running) break@search
                wave.status = "Testing $srcName @ $rate Hz…\n$tried"
                val r = open(src, rate)
                if (r == null) { tried.append("$srcName@$rate: won't open\n"); continue }
                val end = SystemClock.uptimeMillis() + PROBE_MS
                var peak = 0
                var reads = 0
                while (running && SystemClock.uptimeMillis() < end) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) { reads++; for (i in 0 until n) peak = max(peak, abs(buf[i].toInt())) }
                    else SystemClock.sleep(10)
                }
                if (peak > 0) { rec = r; label = "$srcName @ $rate Hz"; break@search }
                tried.append("$srcName@$rate: silent ($reads reads)\n")
                if (fallback == null && reads > 0) fallback = r to "$srcName @ $rate Hz" else r.release()
                break // this source opened but is silent; other rates won't help, try next source
            }
        }
        if (rec == null && fallback != null) { rec = fallback.first; label = fallback.second + " (SILENT)" }
        else fallback?.first?.takeIf { it !== rec }?.release()

        if (rec == null) {
            wave.status = "No microphone could be opened.\n$tried"
            wave.showStatus = true
            running = false
            return
        }

        var peak = 0
        var lastReport = 0L
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) { wave.status = "Mic read error $n ($label)"; SystemClock.sleep(20); continue }
            var sum = 0.0
            for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s; peak = max(peak, abs(buf[i].toInt())) }
            val db = 20.0 * log10(sqrt(sum / n) / 32768.0 + 1e-9)
            wave.targetLevel = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
            wave.pushSamples(buf, n)
            val now = SystemClock.uptimeMillis()
            if (now - lastReport > 250) {
                wave.status = "Mic: $label\nLevel: ${"%.0f".format(db)} dB   peak: $peak / 32767\n" +
                    (if (peak == 0) "All samples are zero: the ROM is giving silence.\n" else "") +
                    "Long-press to hide."
                peak = 0
                lastReport = now
            }
        }
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
    }

    private fun stopMic() {
        running = false
        micThread?.join(2500)
        micThread = null
        wave.targetLevel = 0f
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startMic()
        }
    }
}

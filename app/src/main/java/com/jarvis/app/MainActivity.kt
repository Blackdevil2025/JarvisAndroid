package com.jarvis.app

import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var listening = false
    private lateinit var tts: TextToSpeech
    private lateinit var actions: Actions

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var logText: TextView
    private lateinit var micButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var ring1: View
    private lateinit var ring2: View
    private lateinit var inputText: EditText
    private lateinit var sendButton: Button
    private lateinit var bgSwitch: SwitchCompat

    private val animators = mutableListOf<ValueAnimator>()
    private val chatHistory = mutableListOf<Pair<String, String>>()
    private var autoStartOnReady = false

    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }

    // --- voice model (small by default; "upgrade model" for quiet speech) ---
    private val modelDir get() = File(filesDir, "model")
    private val zipFile get() = File(filesDir, "model.zip")
    private val useBigModel get() = prefs.getBoolean("big_model", false)
    private val modelUrl get() = if (useBigModel)
        "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"
    else
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private val tmpDirName get() = if (useBigModel) "vosk-model-en-us-0.22" else "vosk-model-small-en-us-0.15"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        logText = findViewById(R.id.logText)
        micButton = findViewById(R.id.micButton)
        progressBar = findViewById(R.id.progressBar)
        ring1 = findViewById(R.id.ring1)
        ring2 = findViewById(R.id.ring2)
        inputText = findViewById(R.id.inputText)
        sendButton = findViewById(R.id.sendButton)
        bgSwitch = findViewById(R.id.bgSwitch)

        actions = Actions(this)
        tts = TextToSpeech(this) { s -> if (s == TextToSpeech.SUCCESS) tts.language = Locale.getDefault() }

        styleRings()
        startPulse()

        log("SYS ▸", "J.A.R.V.I.S. OS v3.1 — birthday protocol online")
        log("SYS ▸", "Chat with me by voice or by typing below.")
        log("SYS ▸", "Background listener: toggle ON, then say \"Jarvis\" from anywhere.")
        log("SYS ▸", "Soft voice? Say: upgrade model — download now resumes if interrupted")

        bgSwitch.isChecked = prefs.getBoolean("bg_listener", false)
        bgSwitch.setOnCheckedChangeListener { _, isChecked -> setBackgroundListener(isChecked) }

        sendButton.setOnClickListener {
            val t = inputText.text.toString().trim()
            if (t.isNotBlank()) {
                inputText.setText("")
                handleCommand(t)
            }
        }

        checkPermissions()
        initModel()

        micButton.setOnClickListener { if (listening) stopListening() else startListening() }

        if (intent.getBooleanExtra("wake", false)) tryAutoStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("wake", false)) tryAutoStart()
    }

    private fun tryAutoStart() {
        if (model != null) startListening() else autoStartOnReady = true
    }

    // ------------------------------------------------------------ UI helpers
    private fun setStatus(s: String) = runOnUiThread { statusText.text = s }

    private fun log(tag: String, msg: String) {
        runOnUiThread { logText.append("$tag $msg\n") }
    }

    private fun say(reply: String) {
        log("JARVIS ▸", reply)
        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ringDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), Color.parseColor("#40E0F7FF"))
    }

    private fun styleRings() {
        ring1.background = ringDrawable()
        ring2.background = ringDrawable()
    }

    private fun startPulse() {
        fun pulse(v: View, delay: Long) {
            val va = ValueAnimator.ofFloat(1f, 1.45f)
            va.duration = 1500
            va.repeatCount = ValueAnimator.INFINITE
            va.repeatMode = ValueAnimator.REVERSE
            va.startDelay = delay
            va.addUpdateListener {
                val f = it.animatedValue as Float
                v.scaleX = f; v.scaleY = f
                v.alpha = (1.85f - f).coerceIn(0.08f, 1f)
            }
            va.start()
            animators.add(va)
        }
        pulse(ring1, 0L)
        pulse(ring2, 750L)
    }

    // ------------------------------------------------------------ background listener
    private fun setBackgroundListener(on: Boolean) {
        prefs.edit().putBoolean("bg_listener", on).apply()
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow 'display over other apps' for Jarvis, then toggle again", Toast.LENGTH_LONG).show()
                bgSwitch.isChecked = false
                prefs.edit().putBoolean("bg_listener", false).apply()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))
            Toast.makeText(this, "Background listener on — say \"Jarvis\"", Toast.LENGTH_SHORT).show()
        } else {
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
        }
    }

    // ------------------------------------------------------------ model setup
    private fun initModel() {
        setStatus("LOADING NEURAL CORE…")
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        Thread {
            try {
                if (!File(modelDir, "am/final.mdl").exists()) {
                    downloadModel()
                    setStatus("UNPACKING MODEL…")
                    progressBar.isIndeterminate = true
                    try {
                        unpackModel()
                    } catch (e: Exception) {
                        zipFile.delete()
                        throw e
                    }
                }
                val m = Model(modelDir.absolutePath)
                runOnUiThread {
                    model = m
                    progressBar.visibility = View.GONE
                    setStatus("READY  •  TAP THE CORE OR TYPE BELOW")
                    if (autoStartOnReady) {
                        autoStartOnReady = false
                        startListening()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    setStatus("MODEL ERROR: ${e.message} — RESTART APP, DOWNLOAD RESUMES WHERE IT STOPPED")
                }
            }
        }.start()
    }

    /** Resumable, retrying download with live percentage. Safe for flaky Wi-Fi. */
    private fun downloadModel() {
        var attempt = 0
        while (attempt < 10) {
            attempt++
            try {
                val existing = zipFile.length()
                val conn = URL(modelUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
                val code = conn.responseCode
                if (code == 416) return // file already complete
                if (code != 200 && code != 206) throw Exception("HTTP $code")
                val append = code == 206
                if (!append && existing > 0) zipFile.delete()
                val totalExpected = existing + conn.contentLengthLong
                var received = if (append) existing else 0L
                progressBar.isIndeterminate = false
                progressBar.max = 1000
                conn.inputStream.use { input ->
                    zipFile.outputStream(append).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            received += n
                            if (totalExpected > 0) {
                                val pct = ((received * 100) / totalExpected).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progressBar.progress = ((received * 1000) / totalExpected).toInt()
                                    setStatus("DOWNLOADING VOICE MODEL… $pct%")
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (zipFile.length() > 0) return
            } catch (e: Exception) {
                progressBar.isIndeterminate = true
                setStatus("CONNECTION HICCUP — RESUMING… ($attempt/10)")
                Thread.sleep(2500)
            }
        }
        throw Exception("download kept failing — connect to stronger Wi-Fi and restart")
    }

    private fun unpackModel() {
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val f = File(filesDir, entry.name)
                if (entry.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        val tmp = File(filesDir, tmpDirName)
        if (tmp.exists()) {
            if (modelDir.exists()) modelDir.deleteRecursively()
            tmp.renameTo(modelDir)
        }
        zipFile.delete()
    }

    private fun upgradeModel() {
        prefs.edit().putBoolean("big_model", true).apply()
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        modelDir.deleteRecursively()
        zipFile.delete()
        say("Upgrading to the large neural model. One point eight gigabytes — it resumes automatically if the connection drops. I will hear whispers after this.")
        initModel()
    }

    // ------------------------------------------------------------ listening
    private fun startListening() {
        val m = model ?: run {
            Toast.makeText(this, "Model not ready yet…", Toast.LENGTH_SHORT).show()
            return
        }
        speechService?.stop()
        speechService?.shutdown()
        speechService = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
        speechService!!.startListening(this)
        listening = true
        setStatus("LISTENING…")
        micButton.text = "STOP"
    }

    private fun stopListening() {
        speechService?.stop()
        listening = false
        setStatus("READY  •  TAP THE CORE OR TYPE BELOW")
        micButton.text = "MIC"
    }

    // ------------------------------------------------------------ Vosk callbacks
    override fun onPartialResult(hypothesis: String?) {
        hypothesis?.let { resultText.text = JSONObject(it).optString("partial", "") }
    }

    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            val t = JSONObject(it).optString("text", "")
            if (t.isNotBlank()) handleCommand(t)
        }
        if (listening) speechService?.startListening(this)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(e: Exception?) {
        setStatus("ERROR: ${e?.message}")
    }

    override fun onTimeout() {
        if (listening) speechService?.startListening(this)
    }

    // ------------------------------------------------------------ command + friend brain
    private fun handleCommand(text: String) {
        resultText.text = "» $text"
        log("YOU ▸", text)

        val command = CommandEngine.parse(text)
        if (command.name == "upgrade_model") {
            upgradeModel()
            return
        }
        if (command.name != "unknown") {
            say(actions.execute(command))
            return
        }

        setStatus("THINKING…")
        Brain.think(this, chatHistory, text) { reply ->
            runOnUiThread {
                setStatus(if (listening) "LISTENING…" else "READY  •  TAP THE CORE OR TYPE BELOW")
                chatHistory.add("user" to text)
                chatHistory.add("model" to reply)
                while (chatHistory.size > 12) chatHistory.removeAt(0)
                say(reply)
            }
        }
    }

    // ------------------------------------------------------------ permissions
    private fun checkPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        animators.forEach { it.cancel() }
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        tts.stop()
        tts.shutdown()
    }
}

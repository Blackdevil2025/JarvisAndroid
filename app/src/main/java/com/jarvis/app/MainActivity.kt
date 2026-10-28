package com.jarvis.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
    private lateinit var micButton: Button

    // Downloaded once on first launch, then the app works 100% offline
    private val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private val modelDir get() = File(filesDir, "model")
    private val zipFile get() = File(filesDir, "model.zip")
    private val tmpDirName = "vosk-model-small-en-us-0.15"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        micButton = findViewById(R.id.micButton)

        actions = Actions(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
        }

        checkPermissions()
        initModel()

        micButton.setOnClickListener {
            if (listening) stopListening() else startListening()
        }
    }

    // ------------------------------------------------------------ model setup
    private fun initModel() {
        statusText.text = "Loading speech model…"
        Thread {
            try {
                if (!File(modelDir, "am/final.mdl").exists()) {
                    updateStatus("First run: downloading voice model (~40 MB)…\nWi-Fi recommended. One-time only.")
                    downloadModel()
                    unpackModel()
                    updateStatus("Unpacking model…")
                }
                val m = Model(modelDir.absolutePath)
                runOnUiThread {
                    model = m
                    statusText.text = "Ready. Tap the mic."
                }
            } catch (e: Exception) {
                updateStatus("Model error: ${e.message}\nCheck your connection and restart the app.")
            }
        }.start()
    }

    private fun updateStatus(s: String) = runOnUiThread { statusText.text = s }

    private fun downloadModel() {
        val conn = URL(modelUrl).openConnection() as HttpURLConnection
        conn.inputStream.use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }
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

    private fun startListening() {
        val m = model ?: return
        speechService?.stop()
        speechService?.shutdown()
        speechService = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
        speechService!!.startListening(this)
        listening = true
        statusText.text = "Listening…"
        micButton.text = "STOP"
    }

    private fun stopListening() {
        speechService?.stop()
        listening = false
        statusText.text = "Stopped."
        micButton.text = "MIC"
    }

    // ------------------------------------------------------------ Vosk callbacks
    override fun onPartialResult(hypothesis: String?) {
        hypothesis?.let {
            val t = JSONObject(it).optString("partial", "")
            resultText.text = t
        }
    }

    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            val t = JSONObject(it).optString("text", "")
            if (t.isNotBlank()) handleCommand(t)
        }
        if (listening) speechService?.startListening(this)   // keep listening continuously
    }

    private fun handleCommand(text: String) {
        resultText.text = "» $text"
        val reply = actions.execute(CommandEngine.parse(text))
        speak(reply)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ------------------------------------------------------------ permissions
    private fun checkPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(e: Exception?) {
        statusText.text = "Error: ${e?.message}"
    }
    override fun onTimeout() {
        if (listening) speechService?.startListening(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        tts.stop()
        tts.shutdown()
    }
}

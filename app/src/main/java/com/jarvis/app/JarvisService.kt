package com.jarvis.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale

/**
 * Background listener: an offline keyword spotter that waits for "Jarvis"
 * and answers with voice + a floating orb UI. Works without internet.
 */
class JarvisService : Service(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var pausedUntil = 0L
    private var started = false

    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val modelDir get() = File(filesDir, "model")
    private val zipFile get() = File(filesDir, "model.zip")
    private val useBigModel get() = prefs.getBoolean("big_model", false)
    private val modelUrl get() = if (useBigModel)
        "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"
    else
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private val tmpDirName get() = if (useBigModel) "vosk-model-en-us-0.22" else "vosk-model-small-en-us-0.15"

    companion object {
        const val CHANNEL = "jarvis_bg"
        const val ACTION_STOP = "jarvis.action.STOP"
        const val WAKE_GRAMMAR = "[\"jarvis\",\"hey jarvis\",\"jarvis wake up\",\"wake up jarvis\",\"wake up\"]"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        startForegroundWithNotification()
        addBubble()
        initTts()
        initModel()
        return START_STICKY
    }

    // ------------------------------------------------------------ notification
    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis background listener", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText("Say \"Jarvis\" — I am listening, fully offline")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 1, notif, type)
    }

    // ------------------------------------------------------------ floating orb
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#B300E5FF"))
            setStroke(dp(2), Color.parseColor("#E6FFFFFF"))
        }
        view.background = d
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            dp(52), dp(52), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(24)
        p.y = dp(120)
        view.setOnClickListener { openMain() }
        try {
            wm?.addView(view, p)
            bubble = view
            bubbleParams = p
        } catch (e: Exception) { /* overlay permission missing; notification still works */ }
    }

    private fun pulseBubble() {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        val windowManager = wm ?: return
        val va = ValueAnimator.ofInt(p.width, dp(96))
        va.duration = 420
        va.repeatCount = 3
        va.repeatMode = ValueAnimator.REVERSE
        va.addUpdateListener {
            val s = it.animatedValue as Int
            p.width = s
            p.height = s
            try { windowManager.updateViewLayout(v, p) } catch (e: Exception) {}
        }
        va.start()
    }

    private fun openMain() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("wake", true)
            )
        } catch (e: Exception) {}
    }

    // ------------------------------------------------------------ model + spotting
    private fun initModel() {
        Thread {
            try {
                if (!File(modelDir, "am/final.mdl").exists()) {
                    downloadModel()
                    unpackModel()
                }
                model = Model(modelDir.absolutePath)
                startSpotting()
            } catch (e: Exception) { /* next start will retry */ }
        }.start()
    }

    private fun downloadModel() {
        val conn = java.net.URL(modelUrl).openConnection() as java.net.HttpURLConnection
        conn.inputStream.use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun unpackModel() {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
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

    private fun startSpotting() {
        val m = model ?: return
        speechService?.stop()
        speechService?.shutdown()
        speechService = try {
            SpeechService(Recognizer(m, 16000.0f, WAKE_GRAMMAR), 16000.0f)
        } catch (e: Exception) {
            null
        }
        speechService?.startListening(this)
    }

    // ------------------------------------------------------------ Vosk callbacks
    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        val t = hypothesis?.let { JSONObject(it).optString("text", "") } ?: ""
        if (System.currentTimeMillis() < pausedUntil) {
            startSpotting()
            return
        }
        if (t.contains("jarvis") || t.contains("wake up")) wake() else startSpotting()
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(e: Exception?) {
        startSpotting()
    }

    override fun onTimeout() {
        startSpotting()
    }

    // ------------------------------------------------------------ wake!
    private fun wake() {
        pausedUntil = System.currentTimeMillis() + 9000
        speak(listOf("Yes sir?", "At your service.", "Listening, boss.", "Yes? I am here.").random())
        pulseBubble()
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("wake", true)
            )
        } catch (e: SecurityException) {
            // Android blocked the auto-popup; the orb keeps pulsing until tapped
        } catch (e: Exception) {}
    }

    private fun initTts() {
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-wake")
    }

    private fun shutdown() {
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        tts?.stop()
        tts?.shutdown()
        try { bubble?.let { wm?.removeView(it) } } catch (e: Exception) {}
        started = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        tts?.stop()
        tts?.shutdown()
        try { bubble?.let { wm?.removeView(it) } } catch (e: Exception) {}
        super.onDestroy()
    }
}

/** Starts the listener after reboot if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            if (prefs.getBoolean("bg_listener", false)) {
                ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
            }
        }
    }
}


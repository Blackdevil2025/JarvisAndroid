package com.jarvis.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Executes parsed commands using Android APIs. Everything here runs 100% offline.
 */
class Actions(private val activity: Activity) {

    private val prefs = activity.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun execute(cmd: Command): String {
        return when (cmd.name) {
            "call"        -> call(cmd.args["target"] ?: return "Who should I call?")
            "sms"         -> sendSms(cmd.args["target"] ?: "", cmd.args["message"] ?: "")
            "alarm"       -> setAlarm(cmd.args["time"] ?: "")
            "open_app"    -> openApp(cmd.args["app"] ?: "")
            "flashlight"  -> flashlight(cmd.args["state"] == "on")
            "time"        -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Calendar.getInstance().time)
            "date"        -> SimpleDateFormat("EEEE, MMMM d yyyy", Locale.getDefault()).format(Calendar.getInstance().time)
            "battery"     -> batteryLevel()
            "find_contact"-> findContact(cmd.args["name"] ?: "")
            "note_add"    -> addNote(cmd.args["note"] ?: "")
            "note_read"   -> readNotes()
            "identity"    -> "I am Jarvis, your offline assistant."
            "greeting"    -> "Running at full capacity, thank you."
            "needs_internet" -> "That requires an internet connection, and I work fully offline."
            else          -> "Sorry, I did not understand that command."
        }.also { reply ->
            activity.runOnUiThread { Toast.makeText(activity, reply, Toast.LENGTH_LONG).show() }
        }
    }

    // ------------------------------------------------------------------ calling
    private fun call(target: String): String {
        val number = lookupContact(target) ?: target.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) return "I could not find a number for $target."
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        if (activity.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED) {
            activity.startActivity(intent)
            return "Calling $target."
        }
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        return "Dialing $target. Grant CALL_PHONE permission for auto-dial."
    }

    // ------------------------------------------------------------------ SMS
    private fun sendSms(target: String, message: String): String {
        if (message.isBlank()) return "What should the message say?"
        val number = lookupContact(target) ?: target.filter { it.isDigit() || it == '+' }
        try {
            val sms = activity.getSystemService(SmsManager::class.java)
            sms.sendTextMessage(number, null, message, null, null)
            return "Message sent to $target."
        } catch (e: Exception) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$number"))
                .putExtra("sms_body", message))
            return "Opening SMS app for $target."
        }
    }

    // ------------------------------------------------------------------ alarm
    private fun setAlarm(timeStr: String): String {
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?").find(timeStr) ?: return "What time?"
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val ampm = m.groupValues[3]
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis alarm")
        }
        activity.startActivity(intent)
        return "Alarm set for $timeStr."
    }

    // ------------------------------------------------------------------ open app
    private fun openApp(name: String): String {
        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        for (ri in matches) {
            val label = ri.loadLabel(pm).toString().lowercase()
            if (label.contains(name)) {
                activity.startActivity(pm.getLaunchIntentForPackage(ri.activityInfo.packageName))
                return "Opening $label."
            }
        }
        return "I could not find an app named $name."
    }

    // ------------------------------------------------------------------ flashlight
    private fun flashlight(on: Boolean): String {
        return try {
            val cm = activity.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight found."
            cm.setTorchMode(id, on)
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "Flashlight control is not available on this device."
        }
    }

    // ------------------------------------------------------------------ battery
    private fun batteryLevel(): String {
        val battery = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", -1) ?: -1
        return if (level >= 0) "Battery is at ${(level * 100) / scale} percent." else "Battery status unavailable."
    }

    // ------------------------------------------------------------------ contacts
    private fun lookupContact(name: String): String? {
        if (activity.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null
        val cursor = activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null)
        cursor?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun findContact(name: String): String {
        return lookupContact(name)?.let { "Found $name: $it." }
            ?: "No contact matching $name."
    }

    // ------------------------------------------------------------------ notes
    private fun addNote(note: String): String {
        val notes = prefs.getStringSet("notes", emptySet())!!.toMutableSet()
        notes.add(note)
        prefs.edit().putStringSet("notes", notes).apply()
        return "Noted."
    }

    private fun readNotes(): String {
        val notes = prefs.getStringSet("notes", emptySet())!!
        return if (notes.isEmpty()) "You have no saved notes."
        else "Here are your notes: ${notes.joinToString("; ")}"
    }
}

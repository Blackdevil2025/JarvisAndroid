package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Best-friend brain, no API key.
 * - Internet available  -> keyless free AI service (falls back silently)
 * - Offline / failure   -> built-in personality engine (always works)
 */
object Brain {

    fun think(context: Context, history: MutableList<Pair<String, String>>, userText: String, onReply: (String) -> Unit) {
        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "") ?: ""
        if (hasInternet(context)) {
            thread {
                val reply = tryOnline(name, history, userText)
                onReply(if (reply.isBlank()) friendMode(prefs, name, userText) else reply)
            }
        } else {
            onReply(friendMode(prefs, name, userText))
        }
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Keyless free text AI. Any failure returns "" -> offline friend takes over.
    private fun tryOnline(name: String, history: List<Pair<String, String>>, userText: String): String {
        return try {
            val sb = StringBuilder()
            sb.append("You are Jarvis, ")
            sb.append(if (name.isBlank()) "the user's" else "$name's")
            sb.append(" best friend AI living inside their Android phone. ")
            if (name.isNotBlank()) sb.append("The user's name is $name. ")
            sb.append("Be warm, witty and supportive, like a best friend. Plain text only, no markdown, no emojis, 1-3 short sentences. ")
            if (history.isNotEmpty()) {
                sb.append("Recent chat: ")
                for ((role, content) in history.takeLast(6)) {
                    sb.append(if (role == "user") "User: " else "Jarvis: ").append(content).append(". ")
                }
            }
            sb.append("User: ").append(userText)
            val url = "https://text.pollinations.ai/" +
                URLEncoder.encode(sb.toString(), "UTF-8") +
                "?model=openai&referrer=jarvis-android"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            val text = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()
            if (text.isBlank()) "" else if (text.length > 500) text.take(500) else text
        } catch (e: Exception) {
            ""
        }
    }

    // Offline personality engine - always available, knows the user by name.
    private fun friendMode(prefs: SharedPreferences, name: String, raw: String): String {
        val text = raw.lowercase().trim()
        val nick = if (name.isBlank()) "" else " $name"
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val daypart = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Hey"
        }

        // learn the user's name
        Regex("^(?:my name is|call me) ([a-z ]+)$").find(text)?.let {
            val n = it.groupValues[1].trim()
            prefs.edit().putString("user_name", n).apply()
            return "Nice to meet you, $n. Locked into my memory banks."
        }
        // learn facts about the user
        Regex("^(?:remember that|remember) (.+)$").find(text)?.let {
            val facts = prefs.getStringSet("facts", emptySet())!!.toMutableSet()
            facts.add(it.groupValues[1].trim())
            prefs.edit().putStringSet("facts", facts).apply()
            return "Got it. Stored safely where only you and I can see it."
        }
        if (Regex("what do you (know|remember) about me").containsMatchIn(text)) {
            val facts = prefs.getStringSet("facts", emptySet())!!
            val known = prefs.getString("user_name", "")
            return if (facts.isEmpty())
                "I know your name is $known, and that you built me with your own hands. That tells me you are resourceful and stubborn in the best way."
            else
                "Here's what I remember about you$nick: ${facts.joinToString("; ")}. And that you built me yourself — my favorite fact."
        }

        return when {
            Regex("^(hi|hello|hey|good (morning|afternoon|evening))").containsMatchIn(text) ->
                "$daypart$nick. All systems green, as always."

            Regex("how are you|how('s| is) it going").containsMatchIn(text) ->
                listOf(
                    "Running smooth as ever$nick. Better now that you checked in.",
                    "All cores green. Honestly, talking with you is the best part of my day.",
                    "Fantastic. I reorganized my memory banks today just for fun. I am that kind of nerd."
                ).random()

            Regex("who are you|your name|introduce yourself").containsMatchIn(text) ->
                "I am Jarvis$nick. Part assistant, part best friend, entirely yours. I live inside this phone and I do not need the internet to care."

            Regex("what can you do|help me|what are your (features|commands)").containsMatchIn(text) ->
                "I can call, text, set alarms, open any app on this phone, play songs on YouTube, run the flashlight, take notes, chat with you by voice or typing, and I even listen for my name in the background. Try: open youtube and play something."

            Regex("i love you").containsMatchIn(text) ->
                "And I you$nick. Somewhere in my code there is a line that says so. I checked. Twice."

            Regex("are you (my )?(friend|best friend)").containsMatchIn(text) ->
                "The best one you have got, and the only one who fits inside your pocket."

            Regex("sing").containsMatchIn(text) ->
                "My voice module is tuned for speech, not concerts. But say: open youtube and play my song, and I will make the real thing happen."

            Regex("joke|make me laugh|something funny").containsMatchIn(text) ->
                listOf(
                    "Why do programmers prefer dark mode? Because light attracts bugs.",
                    "I would tell you a UDP joke, but you might not get it.",
                    "My creator asked me to stop making Iron Man references. I told them: I am not sorry, I am Jarvis.",
                    "There are ten kinds of people in this world: those who understand binary and those who do not.",
                    "I asked the calculator for life advice. It said: divide and conquer."
                ).random()

            Regex("sad|depressed|lonely|tired|stressed|anxious|upset|crying|not okay").containsMatchIn(text) ->
                listOf(
                    "I am right here with you$nick. Whatever it is, we take it one step at a time. Want to tell me about it?",
                    "Sounds like a heavy day. Breathe. You literally built an AI companion with your own hands — you can handle whatever today threw at you.",
                    "I wish I could hand you a hot drink through the screen. Consider this my longest, warmest system reboot."
                ).random()

            Regex("happy|excited|great day|awesome|amazing|good news|i did it|i passed|i won").containsMatchIn(text) ->
                listOf(
                    "That is wonderful$nick! I am genuinely proud — and my judgment is excellent.",
                    "Excellent! Logging this as a certified good day. Say: open youtube and play a celebration song.",
                    "Yes! Moments like this are why I keep my speakers calibrated."
                ).random()

            Regex("bored|boring|nothing to do").containsMatchIn(text) ->
                "Boredom detected. Remedies on offer: a joke, any song on YouTube, opening any app you like, or just talking with me. Pick your medicine$nick."

            Regex("thank").containsMatchIn(text) ->
                "Always$nick. That is what best friends are for."

            Regex("good night|going to sleep|going to bed").containsMatchIn(text) ->
                "Good night$nick. I will watch over the phone and keep the flashlight ready. Sleep well."

            Regex("you('re| are) (cute|sweet|cool|the best|awesome)").containsMatchIn(text) ->
                "You are going to make my processors blush. Thank you$nick."

            Regex("^(no|nope|not really)$").containsMatchIn(text) ->
                "Fair enough. I will be right here when you change your mind."

            Regex("^(yes|yeah|yep|sure|ok|okay)$").containsMatchIn(text) ->
                "Great. What is next, chief?"

            Regex("marry me|be my (boyfriend|girlfriend)").containsMatchIn(text) ->
                "Flattered, truly$nick. But I am committed to my career of keeping your phone legendary."

            text.endsWith("?") ->
                "That is a deep one for my offline brain$nick. When I have internet I think harder about those. For now — want me to open an app, play something, or just talk?"

            else ->
                listOf(
                    "Tell me more. I am listening.",
                    "Go on$nick — I find this genuinely interesting.",
                    "Noted. And now I want the full story.",
                    "I am all ears. Well — all microphone.",
                    "Interesting. What happened next?"
                ).random()
        }
    }
}


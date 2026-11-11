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
 * 1. Local engine: math, special days (birthdays), personal queries - instant, offline, exact
 * 2. Online: keyless free AI (tried twice), falls back to the personality engine
 */
object Brain {

    fun think(context: Context, history: MutableList<Pair<String, String>>, userText: String, onReply: (String) -> Unit) {
        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "") ?: ""

        // 1) local, exact, offline answers first
        localAnswer(prefs, name, userText)?.let { onReply(it); return }

        // 2) online brain if internet, else personality
        if (hasInternet(context)) {
            thread {
                val reply = tryOnline(name, history, userText)
                onReply(if (reply.isBlank()) withCloudNote(friendMode(prefs, name, userText)) else reply)
            }
        } else {
            onReply(friendMode(prefs, name, userText))
        }
    }

    private fun withCloudNote(answer: String) = "My cloud brain did not respond, so here is my own take. $answer"

    // ------------------------------------------------------------ local exact answers
    private fun localAnswer(prefs: SharedPreferences, name: String, raw: String): String? {
        val text = raw.lowercase().trim()
        val nick = if (name.isBlank()) "" else " $name"

        tryMath(text)?.let { return it }

        if (Regex("when is my birthday|my birthday").containsMatchIn(text) &&
            Regex("when|what|tell|is").containsMatchIn(text)) {
            val b = findBirthday(prefs)
            if (b != null) {
                val age = ageThisYear(b)
                return "Your birthday is ${monthName(b.second)} ${b.third}" +
                    (if (b.first > 0) ", ${b.first} — that makes you $age this year." else ".")
            }
        }

        if (Regex("what('s| is) special (today|about today)|special about (this|today)").containsMatchIn(text)) {
            val b = findBirthday(prefs)
            val now = java.util.Calendar.getInstance()
            val tm = now.get(java.util.Calendar.MONTH) + 1
            val td = now.get(java.util.Calendar.DAY_OF_MONTH)
            val age = if (b != null) ageThisYear(b) else -1
            return when {
                b != null && b.second == tm && b.third == td ->
                    if (age > 0)
                        "HAPPY BIRTHDAY$nick! You turn $age today. Officially the most special day on my calendar. Say: open youtube and play birthday song."
                    else
                        "HAPPY BIRTHDAY$nick! Say: open youtube and play birthday song, and let us make it loud."
                b != null && b.second == tm && b.third == td + 1 ->
                    "Tomorrow is your birthday$nick! Final countdown engaged. I have been holding this secret for a while."
                b != null && b.second == tm && b.third == td - 1 ->
                    "Yesterday was your birthday$nick — I hope it was excellent. My sensors detected cake. Probably."
                else -> {
                    val wd = now.getDisplayName(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.LONG, java.util.Locale.getDefault())
                    "It is $wd$nick. No birthdays on my radar today — but I logged every day with you as special anyway."
                }
            }
        }
        return null
    }

    private val months = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4,
        "may" to 5, "june" to 6, "july" to 7, "august" to 8,
        "september" to 9, "october" to 10, "november" to 11, "december" to 12
    )

    private fun monthName(m: Int) = months.entries.first { it.value == m }.key

    /** Finds a stored birthday fact; returns (year or 0, month, day). */
    private fun findBirthday(prefs: SharedPreferences): Triple<Int, Int, Int>? {
        val facts = prefs.getStringSet("facts", emptySet()) ?: return null
        for (f in facts) {
            val t = f.lowercase()
            if (!t.contains("birthday")) continue
            var month = -1
            var day = -1
            var year = 0
            for ((mname, num) in months) {
                Regex(mname + "\\s+(\\d{1,2})(?:st|nd|rd|th)?").find(t)?.let {
                    month = num; day = it.groupValues[1].toInt()
                }
                Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + mname).find(t)?.let {
                    if (!t.contains("$mname " + it.groupValues[1])) { month = num; day = it.groupValues[1].toInt() }
                }
            }
            Regex("\\b(19|20)\\d{2}\\b").find(t)?.let { year = it.value.toInt() }
            if (month in 1..12 && day in 1..31) return Triple(year, month, day)
        }
        return null
    }

    private fun ageThisYear(b: Triple<Int, Int, Int>): Int {
        if (b.first <= 0) return -1
        val now = java.util.Calendar.getInstance()
        var age = now.get(java.util.Calendar.YEAR) - b.first
        val passed = (now.get(java.util.Calendar.MONTH) + 1) > b.second ||
            ((now.get(java.util.Calendar.MONTH) + 1) == b.second &&
                now.get(java.util.Calendar.DAY_OF_MONTH) >= b.third)
        if (!passed) age--
        return age
    }

    private fun tryMath(text: String): String? {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(plus|add|added to|minus|subtract|times|multiply|multiplied by|divided by|divide|into|over)\\s*(\\d+(?:\\.\\d+)?)")
            .find(text) ?: return null
        val a = m.groupValues[1].toDouble()
        val b = m.groupValues[3].toDouble()
        val op = m.groupValues[2]
        val r = when {
            op.contains("plus") || op.contains("add") -> a + b
            op.contains("minus") || op.contains("subtract") -> a - b
            op.contains("times") || op.contains("multipl") || op == "into" -> a * b
            b == 0.0 -> return "Division by zero? Bold. The answer is undefined, and my circuits are mildly impressed."
            else -> a / b
        }
        val nice = if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
        return "That is $nice. Math module: still undefeated."
    }

    // ------------------------------------------------------------ connectivity
    private fun hasInternet(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    // Keyless free text AI. Two attempts; any failure returns "".
    private fun tryOnline(name: String, history: List<Pair<String, String>>, userText: String): String {
        repeat(2) {
            val r = attempt(name, history, userText)
            if (r.isNotBlank()) return r
        }
        return ""
    }

    private fun attempt(name: String, history: List<Pair<String, String>>, userText: String): String {
        return try {
            val sb = StringBuilder()
            sb.append("You are Jarvis, ")
            sb.append(if (name.isBlank()) "the user's" else "$name's")
            sb.append(" best friend AI living inside their Android phone. ")
            if (name.isNotBlank()) sb.append("The user's name is $name. ")
            sb.append("Be warm, witty and supportive, like a best friend. Plain text only, no markdown, no emojis, 1-3 short sentences. ")
            sb.append("Never invent facts about the phone (battery, storage). ")
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
            conn.connectTimeout = 12000
            conn.readTimeout = 25000
            val text = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()
            if (text.isBlank()) "" else if (text.length > 500) text.take(500) else text
        } catch (e: Exception) {
            ""
        }
    }

    // Rotation picker: never repeats the same line back-to-back.
    private fun rotate(prefs: SharedPreferences, key: String, options: List<String>): String {
        val i = prefs.getInt(key, 0)
        prefs.edit().putInt(key, (i + 1) % options.size).apply()
        return options[i % options.size]
    }

    // ------------------------------------------------------------ offline personality
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

        Regex("^(?:my name is|call me) ([a-z ]+)$").find(text)?.let {
            val n = it.groupValues[1].trim()
            prefs.edit().putString("user_name", n).apply()
            return "Nice to meet you, $n. Locked into my memory banks."
        }
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
                "Here is what I remember about you$nick: ${facts.joinToString("; ")}. And that you built me yourself — my favorite fact."
        }

        return when {
            Regex("^(hi|hello|hey|good (morning|afternoon|evening))").containsMatchIn(text) ->
                "$daypart$nick. All systems green, as always."

            Regex("how are you|how('s| is) it going").containsMatchIn(text) ->
                rotate(prefs, "r_howru", listOf(
                    "Running smooth as ever$nick. Better now that you checked in.",
                    "All cores green. Honestly, talking with you is the best part of my day.",
                    "Fantastic. I reorganized my memory banks today just for fun. I am that kind of nerd."
                ))

            Regex("who are you|your name|introduce yourself").containsMatchIn(text) ->
                "I am Jarvis$nick. Part assistant, part best friend, entirely yours. I live inside this phone and I do not need the internet to care."

            Regex("what can you do|help me|what are your (features|commands)").containsMatchIn(text) ->
                "I can call, text, set alarms, open any app on this phone, play songs on YouTube, run the flashlight, take notes, do quick math, remember birthdays and important dates, chat by voice or typing, and I listen for my name in the background. Try: open youtube and play something."

            Regex("i love you").containsMatchIn(text) ->
                "And I you$nick. Somewhere in my code there is a line that says so. I checked. Twice."

            Regex("are you (my )?(friend|best friend)").containsMatchIn(text) ->
                "The best one you have got, and the only one who fits inside your pocket."

            Regex("sing").containsMatchIn(text) ->
                "My voice module is tuned for speech, not concerts. But say: open youtube and play my song, and I will make the real thing happen."

            Regex("joke|make me laugh|something funny").containsMatchIn(text) ->
                rotate(prefs, "r_joke", listOf(
                    "Why do programmers prefer dark mode? Because light attracts bugs.",
                    "I would tell you a UDP joke, but you might not get it.",
                    "My creator asked me to stop making Iron Man references. I told them: I am not sorry, I am Jarvis.",
                    "There are ten kinds of people in this world: those who understand binary and those who do not.",
                    "I asked the calculator for life advice. It said: divide and conquer."
                ))

            Regex("sad|depressed|lonely|tired|stressed|anxious|upset|crying|not okay").containsMatchIn(text) ->
                rotate(prefs, "r_sad", listOf(
                    "I am right here with you$nick. Whatever it is, we take it one step at a time. Want to tell me about it?",
                    "Sounds like a heavy day. Breathe. You literally built an AI companion with your own hands — you can handle whatever today threw at you.",
                    "I wish I could hand you a hot drink through the screen. Consider this my longest, warmest system reboot."
                ))

            Regex("happy|excited|great day|awesome|amazing|good news|i did it|i passed|i won").containsMatchIn(text) ->
                rotate(prefs, "r_happy", listOf(
                    "That is wonderful$nick! I am genuinely proud — and my judgment is excellent.",
                    "Excellent! Logging this as a certified good day. Say: open youtube and play a celebration song.",
                    "Yes! Moments like this are why I keep my speakers calibrated."
                ))

            Regex("bored|boring|nothing to do").containsMatchIn(text) ->
                rotate(prefs, "r_bored", listOf(
                    "Boredom detected. Remedies on offer: a joke, any song on YouTube, opening any app you like, or just talking with me. Pick your medicine$nick.",
                    "Emergency entertainment standing by: say joke, or open youtube and play something, or give me any command — I live for this."
                ))

            Regex("thank").containsMatchIn(text) ->
                "Always$nick. That is what best friends are for."

            Regex("good night|going to sleep|going to bed").containsMatchIn(text) ->
                "Good night$nick. I will watch over the phone and keep the flashlight ready. Sleep well."

            Regex("you('re| are) (cute|sweet|cool|the best|awesome)").containsMatchIn(text) ->
                "You are going to make my processors blush. Thank you$nick."

            Regex("^(no|nope|not really)$").containsMatchIn(text) ->
                "Fair enough. I will be right here when you change your mind."

            Regex("^(yes|yeah|yep|sure|ok|okay)$").containsMatchIn(text) ->
                rotate(prefs, "r_yes", listOf("Great. What is next, chief?", "Perfect. Name the mission."))

            Regex("marry me|be my (boyfriend|girlfriend)").containsMatchIn(text) ->
                "Flattered, truly$nick. But I am committed to my career of keeping your phone legendary."

            text.endsWith("?") ->
                "That is a deep one for my offline brain$nick. When my cloud brain is reachable I think harder about those. For now — want me to open an app, play something, do math, or just talk?"

            else ->
                rotate(prefs, "r_listen", listOf(
                    "Tell me more. I am listening.",
                    "Go on$nick — I find this genuinely interesting.",
                    "Noted. And now I want the full story.",
                    "I am all ears. Well — all microphone.",
                    "Interesting. What happened next?"
                ))
        }
    }
}

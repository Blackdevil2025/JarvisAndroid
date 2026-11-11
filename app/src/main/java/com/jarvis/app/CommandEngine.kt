package com.jarvis.app

import java.text.Normalizer
import java.util.Locale

/**
 * Offline natural-language command parser.
 * Normalizes common speech-recognition noise and conversational phrasing before
 * matching commands. Unknown requests are deliberately passed to the original
 * Brain implementation; this parser never replaces or edits the user's AI.
 */
data class Command(val name: String, val args: Map<String, String> = emptyMap())

object CommandEngine {

    fun parse(raw: String): Command {
        val text = normalize(raw)

        // --- Calling -------------------------------------------------------
        Regex("^(?:please )?(?:can you )?(?:call|phone|dial) (.+)$").find(text)?.let {
            return Command("call", mapOf("target" to cleanTarget(it.groupValues[1])))
        }

        // --- SMS -----------------------------------------------------------
        Regex("^(?:please )?(?:text|message) (.+?) (?:saying|that says|and say) (.+)$").find(text)?.let {
            return Command("sms", mapOf("target" to cleanTarget(it.groupValues[1]), "message" to it.groupValues[2]))
        }
        Regex("^(?:please )?(?:send|text) (?:a )?(?:message )?to (.+?) (?:that says|saying|says) (.+)$").find(text)?.let {
            return Command("sms", mapOf("target" to cleanTarget(it.groupValues[1]), "message" to it.groupValues[2]))
        }

        // --- Alarm ---------------------------------------------------------
        val alarmText = text
            .replace(Regex("^(?:please )?(?:set|create) (?:an? )?alarm (?:for )?"), "alarm ")
            .replace(Regex("^(?:please )?wake me up at "), "alarm ")
            .replace(Regex("^(?:please )?remind me to wake up at "), "alarm ")
            .replace(Regex("^alarm for "), "alarm ")
        Regex("^alarm (\\d{1,2})(?::(\\d{2}))?(?:\\s?(am|pm))?$").find(alarmText)?.let {
            val hour = it.groupValues[1].toIntOrNull() ?: return Command("unknown")
            val minute = it.groupValues[2].ifEmpty { "00" }
            if (hour !in 1..12 || minute.toIntOrNull() !in 0..59) return Command("unknown")
            return Command("alarm", mapOf("time" to "${it.groupValues[1]}:$minute ${it.groupValues[3]}".trim()))
        }

        // --- YouTube -------------------------------------------------------
        Regex("^(?:please )?(?:open )?youtube(?: and)?(?: play)?(?: (.+))?$").find(text)?.let {
            return Command("youtube", mapOf("query" to (it.groupValues.getOrNull(1) ?: "").trim()))
        }
        Regex("^(?:please )?(?:play|search for) (.+?) (?:on|in) youtube$").find(text)?.let {
            return Command("youtube", mapOf("query" to it.groupValues[1]))
        }
        Regex("^(?:please )?(?:play|put on) (.+)$").find(text)?.let {
            val request = it.groupValues[1]
            if (request.startsWith("music ") || request.startsWith("song "))
                return Command("youtube", mapOf("query" to request.substringAfter(' ')))
        }

        // --- Open an app ---------------------------------------------------
        Regex("^(?:please )?(?:can you )?(?:open|launch|start) (.+)$").find(text)?.let {
            val app = it.groupValues[1].removePrefix("the ").removeSuffix(" please").trim()
            return Command("open_app", mapOf("app" to app))
        }

        // --- Flashlight ----------------------------------------------------
        Regex("^(?:please )?(?:turn|switch) (on|off) (?:the )?(?:flash ?light|torch)$").find(text)?.let {
            return Command("flashlight", mapOf("state" to it.groupValues[1]))
        }
        if (text in setOf("flashlight on", "torch on")) return Command("flashlight", mapOf("state" to "on"))
        if (text in setOf("flashlight off", "torch off")) return Command("flashlight", mapOf("state" to "off"))

        // --- Time / date ---------------------------------------------------
        if (Regex("\\b(what time is it|tell me the time|current time|time now)\\b").containsMatchIn(text))
            return Command("time")
        if (Regex("\\b(what is the date|what's the date|today's date|date today|what day is it)\\b").containsMatchIn(text))
            return Command("date")

        // --- Battery -------------------------------------------------------
        if (Regex("\\b(battery|charge|power)\\b").containsMatchIn(text) &&
            Regex("\\b(how much|what is|what's|check|tell me|level|left|remaining|status)\\b").containsMatchIn(text))
            return Command("battery")

        // --- Contacts ------------------------------------------------------
        Regex("^(?:please )?(?:find|search for|who is|look up) (.+?) (?:in )?(?:my )?contacts?$|^(?:call|phone) contact (.+)$").find(text)?.let {
            val contact = it.groupValues[1].ifBlank { it.groupValues[2] }
            return Command("find_contact", mapOf("name" to cleanTarget(contact)))
        }

        // --- Notes ---------------------------------------------------------
        Regex("^(?:please )?(?:take a note|remember this|note down|make a note|save a note) (?:that )?(.+)$").find(text)?.let {
            return Command("note_add", mapOf("note" to it.groupValues[1]))
        }
        if (Regex("^(?:please )?(?:read|show) (?:my )?notes?$|what did i ask you to remember$").containsMatchIn(text))
            return Command("note_read")

        // --- Model upgrade -------------------------------------------------
        if (Regex("\\b(upgrade (the )?model|use (the )?big (model|brain)|improve (my )?hearing)\\b").containsMatchIn(text))
            return Command("upgrade_model")

        // --- Small talk / identity ----------------------------------------
        if (Regex("\\b(who are you|what is your name|what's your name|your name|introduce yourself)\\b").containsMatchIn(text))
            return Command("identity")
        if (Regex("\\b(how are you|how's it going|how is it going)\\b").containsMatchIn(text))
            return Command("greeting")

        // Preserve the existing AI path for every unrecognized sentence.
        return Command("unknown")
    }

    /** Conservative cleanup: remove speech fillers, punctuation and common dictation artifacts. */
    private fun normalize(raw: String): String {
        var value = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9:@'+ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        value = value.replace(Regex("^(?:um+|uh+|erm+|hmm+|hey)\\s+"), "")
        value = value.replace(Regex("\\s+(?:please|thanks|thank you)$"), "")
        // Common speech-to-text variants for the wake name and device controls.
        value = value.replace(Regex("\\bjarvis\\b"), "jarvis")
        value = value.replace(Regex("\\bflash light\\b"), "flashlight")
        return value.trim()
    }

    private fun cleanTarget(value: String) = value.trim().removePrefix("the ").removeSuffix(" please").trim()
}

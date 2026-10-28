package com.jarvis.app

/**
 * Rule-based offline natural language -> command parser.
 * Parses raw speech text into a structured Command object.
 * Extend this file to add new capabilities.
 */
data class Command(val name: String, val args: Map<String, String> = emptyMap())

object CommandEngine {

    fun parse(raw: String): Command {
        val text = raw.lowercase()
            .replace(Regex("[^a-z0-9:@' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // --- Calling -------------------------------------------------------
        Regex("^(call|phone|dial) (.+)$").find(text)?.let {
            return Command("call", mapOf("target" to it.groupValues[2]))
        }

        // --- SMS -----------------------------------------------------------
        Regex("^text (.+?) saying (.+)$").find(text)?.let {
            return Command("sms", mapOf("target" to it.groupValues[1], "message" to it.groupValues[2]))
        }
        Regex("^(send|text) (?:a )?(?:message )?to (.+?) that says (.+)$").find(text)?.let {
            return Command("sms", mapOf("target" to it.groupValues[2], "message" to it.groupValues[3]))
        }

        // --- Alarm ---------------------------------------------------------
        Regex("^(?:set |wake me at )?(?:an )?alarm (?:for )?(\\d{1,2})(?::(\\d{2}))?(?:\\s?(am|pm))?$").find(text)?.let {
            return Command("alarm", mapOf(
                "time" to "${it.groupValues[1]}:${it.groupValues[2].ifEmpty { "00" }} ${it.groupValues[3]}"
            ))
        }
        Regex("^(?:wake me up at )(\\d{1,2})(?::(\\d{2}))?(?:\\s?(am|pm))?$").find(text)?.let {
            return Command("alarm", mapOf(
                "time" to "${it.groupValues[1]}:${it.groupValues[2].ifEmpty { "00" }} ${it.groupValues[3]}"
            ))
        }

        // --- Open an app ---------------------------------------------------
        Regex("^(?:open|launch|start) (.+)$").find(text)?.let {
            return Command("open_app", mapOf("app" to it.groupValues[1]))
        }

        // --- Flashlight ------------------------------------------------------
        Regex("^turn (on|off) (?:the )?(?:flash ?light|torch)$").find(text)?.let {
            return Command("flashlight", mapOf("state" to it.groupValues[1]))
        }

        // --- Time / date -----------------------------------------------------
        if (Regex("what('s| is) the time|what time is it|tell me the time").containsMatchIn(text))
            return Command("time")
        if (Regex("what('s| is) the (date|day)|what date|what day is").containsMatchIn(text))
            return Command("date")

        // --- Battery ----------------------------------------------------------
        if (Regex("battery|how much charge|power left").containsMatchIn(text))
            return Command("battery")

        // --- Contacts ---------------------------------------------------------
        Regex("^(?:find|search|who is) (.+) (?:in )?(?:my )?contacts?$").find(text)?.let {
            return Command("find_contact", mapOf("name" to it.groupValues[1]))
        }

        // --- Notes -------------------------------------------------------------
        Regex("^(?:take a note|remember this|note) (?:that )?(.+)$").find(text)?.let {
            return Command("note_add", mapOf("note" to it.groupValues[1]))
        }
        if (Regex("read (my )?notes|read (my )?note").containsMatchIn(text))
            return Command("note_read")

        // --- Small talk / identity ----------------------------------------------
        if (Regex("who are you|your name|introduce yourself").containsMatchIn(text))
            return Command("identity")
        if (Regex("how are you|how('s| is) it going").containsMatchIn(text))
            return Command("greeting")

        // --- Fallback: needs internet things -------------------------------------
        if (Regex("weather|news|search the web|google|youtube").containsMatchIn(text))
            return Command("needs_internet")

        return Command("unknown")
    }
}

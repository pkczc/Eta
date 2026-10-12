package io.github.mangi.eta.hook.breeno

import org.json.JSONArray
import org.json.JSONObject

/** Inspect an entire batch before any native card or action in it can execute. */
internal class BreenoNativeMessage private constructor(
    private val message: JSONObject,
    private val fallbackRoomId: String,
) {
    private val directives = message.getJSONArray("directives")
    val isInjected: Boolean = message.optJSONObject("extend")?.optString("etaAgent") == "true"

    data class Query(
        val text: String,
        val roomId: String,
        val recordId: String,
        val originalRecordId: String,
        val sessionId: String,
        val queryPayload: String?,
    )

    fun queries(): List<Query> {
        if (isInjected) return emptyList()
        return (0 until directives.length()).mapNotNull { index ->
            val directive = directives.optJSONObject(index) ?: return@mapNotNull null
            val header = directive.optJSONObject("header") ?: return@mapNotNull null
            val payload = directive.optJSONObject("payload") ?: return@mapNotNull null
            val text = when {
                isQueryCard(directive) && payload.opt("isFinal") != false ->
                    (payload.opt("content") as? String).orEmpty().trim()
                header.optString("namespace") == "SpeechRecognizer" &&
                    header.optString("name") == "RecognizeResult" &&
                    (payload.opt("isFinal") ?: payload.opt("final")) == true ->
                    finalRecognitionText(payload)
                else -> ""
            }
            val roomId = roomId(directive)
            if (text.isBlank() || roomId.isBlank()) return@mapNotNull null
            Query(
                text = text,
                roomId = roomId,
                recordId = message.string("recordId"),
                originalRecordId = message.string("originalRecordId"),
                sessionId = message.string("sessionId"),
                queryPayload = if (isQueryCard(directive)) payload.toString() else null,
            )
        }.distinctBy { it.roomId to it.text }
    }

    fun retainedIndices(isClaimed: (String) -> Boolean): List<Int> =
        (0 until directives.length()).filter { index ->
            if (isInjected) return@filter true
            val directive = directives.optJSONObject(index) ?: return@filter true
            val roomId = roomId(directive)
            roomId.isBlank() || !isClaimed(roomId) || keepDuringTakeover(directive)
        }

    fun filteredContent(indices: List<Int>): String? {
        if (indices.size == directives.length()) return message.toString()
        if (indices.isEmpty()) return null
        val kept = JSONArray()
        indices.forEach { kept.put(directives.get(it)) }
        return JSONObject(message.toString()).put("directives", kept).toString()
    }

    val directiveCount: Int get() = directives.length()

    private fun roomId(directive: JSONObject): String =
        directive.optJSONObject("payload")?.string("roomId").orEmpty()
            .ifBlank { message.string("roomId") }
            .ifBlank { fallbackRoomId }

    private fun JSONObject.string(key: String): String = (opt(key) as? String).orEmpty()

    private fun isQueryCard(directive: JSONObject): Boolean {
        val header = directive.optJSONObject("header") ?: return false
        return header.optString("namespace") == "MyAI" &&
            header.optString("name") == "StreamTextCard" &&
            directive.optJSONObject("payload")?.optInt("type", -1) == 1
    }

    private fun keepDuringTakeover(directive: JSONObject): Boolean {
        if (isQueryCard(directive)) return true
        val header = directive.optJSONObject("header") ?: return true
        return when (header.optString("namespace")) {
            // Keep recognition and session cleanup; suppress native answers, TTS,
            // recommendations and actions, including previously unknown card types.
            "SpeechRecognizer" -> header.optString("name") != "ExpectSpeech"
            "Dialog", "Session" -> true
            "" -> true
            else -> false
        }
    }

    private fun finalRecognitionText(payload: JSONObject): String {
        val text = (payload.opt("text") as? String).orEmpty().trim()
        if (text.isNotBlank()) return text
        val results = payload.optJSONArray("results") ?: return ""
        return (0 until results.length()).joinToString("") { index ->
            (results.optJSONObject(index)?.opt("text") as? String).orEmpty()
        }.trim()
    }

    companion object {
        const val MAX_CONTENT_CHARS = 512 * 1024

        fun parse(content: String?, fallbackRoomId: String = ""): BreenoNativeMessage? {
            if (content.isNullOrBlank() || content.length > MAX_CONTENT_CHARS) return null
            return runCatching {
                val message = JSONObject(content)
                if (message.optJSONArray("directives") == null) return null
                BreenoNativeMessage(message, fallbackRoomId)
            }.getOrNull()
        }
    }
}

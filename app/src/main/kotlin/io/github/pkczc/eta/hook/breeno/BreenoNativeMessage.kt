package io.github.pkczc.eta.hook.breeno

import org.json.JSONArray
import org.json.JSONObject

internal object BreenoNativeMessage {
    const val MAX_CONTENT_CHARS = 512 * 1024

    fun filter(content: String?, fallbackRoomId: String, isClaimed: (String) -> Boolean): String? {
        if (content.isNullOrBlank() || content.length > MAX_CONTENT_CHARS) return content
        val message = JSONObject(content)
        if (message.optJSONObject("extend")?.optString("etaAgent") == "true") return content
        val directives = message.optJSONArray("directives") ?: return content
        val kept = JSONArray()
        for (index in 0 until directives.length()) {
            val directive = directives.optJSONObject(index)
            val payload = directive?.optJSONObject("payload")
            val roomId = (payload?.opt("roomId") as? String).orEmpty()
                .ifBlank { (message.opt("roomId") as? String).orEmpty() }
                .ifBlank { fallbackRoomId }
            val header = directive?.optJSONObject("header")
            val namespace = header?.optString("namespace").orEmpty()
            val name = header?.optString("name").orEmpty()
            val queryCard = namespace == "MyAI" && name == "StreamTextCard" && payload?.optInt("type", -1) == 1
            if (roomId.isBlank() || !isClaimed(roomId) || queryCard || !suppresses(namespace, name)) {
                kept.put(directives.get(index))
            }
        }
        if (kept.length() == directives.length()) return content
        return if (kept.length() == 0) null else message.put("directives", kept).toString()
    }

    private fun suppresses(namespace: String, name: String): Boolean = when (namespace) {
        "PhoneManager", "SystemStorage", "System", "SystemScreen", "App",
        "Sms", "PhoneCall", "Ocr", "AnalogClick", "Recommend", "SpeechSynthesizer" -> true
        "MyAI" -> name == "LoadingStateCard" || name == "StreamTextCard"
        "Tracking" -> name == "BreenoFeedback"
        "SpeechRecognizer" -> name == "ExpectSpeech"
        else -> false
    }
}

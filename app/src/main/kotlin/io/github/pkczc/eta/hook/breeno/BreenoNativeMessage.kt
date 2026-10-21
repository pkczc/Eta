package io.github.pkczc.eta.hook.breeno

import org.json.JSONArray
import org.json.JSONObject

internal object BreenoNativeMessage {
    const val MAX_CONTENT_CHARS = 512 * 1024

    fun filter(content: String?, agentTurnClaimed: Boolean): String? {
        if (!agentTurnClaimed || content.isNullOrBlank() || content.length > MAX_CONTENT_CHARS) return content
        val message = JSONObject(content)
        if (isEtaInjected(message)) return content
        val directives = message.optJSONArray("directives") ?: return content
        val kept = JSONArray()
        for (index in 0 until directives.length()) {
            val directive = directives.optJSONObject(index)
            val payload = directive?.optJSONObject("payload")
            val header = directive?.optJSONObject("header")
            val namespace = header?.optString("namespace").orEmpty()
            val name = header?.optString("name").orEmpty()
            if (!suppresses(namespace, name, payload?.optInt("type", -1))) {
                kept.put(directives.get(index))
            }
        }
        if (kept.length() == directives.length()) return content
        return if (kept.length() == 0) null else message.put("directives", kept).toString()
    }

    fun isEtaInjected(content: String?): Boolean =
        !content.isNullOrBlank() && content.length <= MAX_CONTENT_CHARS && isEtaInjected(JSONObject(content))

    private fun isEtaInjected(message: JSONObject): Boolean =
        message.optJSONObject("extend")?.optString("etaAgent") == "true"

    fun suppresses(namespace: String, name: String, cardType: Int?): Boolean = when (namespace) {
        "PhoneManager", "SystemStorage", "System", "SystemScreen", "App",
        "Sms", "PhoneCall", "Ocr", "AnalogClick", "Recommend", "SpeechSynthesizer" -> true
        "MyAI" -> name == "LoadingStateCard" || (name == "StreamTextCard" && cardType != 1)
        "Tracking" -> name == "BreenoFeedback"
        "SpeechRecognizer" -> name == "ExpectSpeech"
        else -> false
    }
}

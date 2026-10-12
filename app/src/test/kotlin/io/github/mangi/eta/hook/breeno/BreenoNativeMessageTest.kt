package io.github.mangi.eta.hook.breeno

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BreenoNativeMessageTest {
    @Test
    fun claimedVoiceTurnKeepsInputAndRecognitionButDropsNativeActionAnswerAndTts() {
        val raw = message(
            directive("System", "SetVolume"),
            directive("SpeechRecognizer", "RecognizeResult"),
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 1).put("content", "调低音量")),
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 2).put("content", "已调低")),
            directive("SpeechSynthesizer", "Speak"),
            directive("Session", "Finish"),
        ).toString()

        val filtered = JSONObject(requireNotNull(filter(raw)))

        assertEquals("record-1", filtered.getString("recordId"))
        assertEquals(listOf("RecognizeResult", "StreamTextCard", "Finish"), names(filtered))
        assertEquals("调低音量", filtered.getJSONArray("directives").getJSONObject(1).getJSONObject("payload").getString("content"))
    }

    @Test
    fun nativeOrReleasedTurnKeepsTheOriginalPacket() {
        val raw = message(directive("System", "SetVolume"), directive("SpeechSynthesizer", "Speak")).toString()

        assertEquals(raw, BreenoNativeMessage.filter(raw, "room-1") { false })
    }

    @Test
    fun etaInjectedFramesBypassNativeAnswerFiltering() {
        val raw = message(directive("MyAI", "StreamTextCard", JSONObject().put("type", 2)))
            .put("extend", JSONObject().put("etaAgent", true)).toString()

        assertEquals(raw, filter(raw))
    }

    @Test
    fun missingRoomUsesTheCapturedCurrentRoom() {
        val json = message(directive("SpeechSynthesizer", "Speak")).apply { remove("roomId") }

        assertNull(filter(json.toString()))
        assertEquals(json.toString(), BreenoNativeMessage.filter(json.toString(), "") { true })
    }

    @Test
    fun payloadRoomOverridesPacketAndCurrentRoom() {
        val json = message(
            directive("System", "SetVolume", JSONObject().put("roomId", "room-2")),
            directive("System", "SetVolume"),
        )
        val result = JSONObject(requireNotNull(filter(json.toString())))

        assertEquals(1, result.getJSONArray("directives").length())
        assertEquals("room-2", result.getJSONArray("directives").getJSONObject(0).getJSONObject("payload").getString("roomId"))
    }

    @Test
    fun cleanupAndStreamingUpdatesAreSuppressedAlongWithRecommendations() {
        val json = message(
            directive("PhoneManager", "Clean"),
            directive("SystemStorage", "Clean"),
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 0)),
            directive("Recommend", "Related"),
            directive("SpeechRecognizer", "ExpectSpeech"),
        )

        assertNull(filter(json.toString()))
    }

    @Test
    fun lifecycleAndUnrecognizedEntriesArePreserved() {
        val json = message(directive("SpeechRecognizer", "StopCapture"), directive("Dialog", "Finish"))
        json.getJSONArray("directives").put(JSONObject()).put(JSONObject.NULL)

        assertEquals(json.toString(), filter(json.toString()))
    }

    @Test
    fun nullRoomMetadataUsesCurrentRoomWithoutCreatingALiteralNullRoom() {
        val raw = message(directive("System", "SetVolume")).put("roomId", JSONObject.NULL).toString()

        assertNull(filter(raw))
        assertEquals(raw, BreenoNativeMessage.filter(raw, "") { true })
    }

    @Test
    fun emptyAndOversizedPacketsArePassedWithoutOwnershipLookup() {
        val tooLarge = "x".repeat(BreenoNativeMessage.MAX_CONTENT_CHARS + 1)

        assertEquals(tooLarge, BreenoNativeMessage.filter(tooLarge, "room-1") { error("must not inspect oversized packet") })
        assertEquals("{}", filter("{}"))
        assertEquals("", filter(""))
        assertNull(filter(null))
    }

    @Test(expected = JSONException::class)
    fun malformedJsonReachesTheHookErrorBoundary() {
        filter("not JSON")
    }

    private fun filter(raw: String?): String? =
        BreenoNativeMessage.filter(raw, "room-1") { it == "room-1" }

    private fun names(message: JSONObject): List<String> {
        val directives = message.getJSONArray("directives")
        return (0 until directives.length()).map { directives.getJSONObject(it).getJSONObject("header").getString("name") }
    }

    private fun message(vararg directives: JSONObject): JSONObject = JSONObject()
        .put("roomId", "room-1")
        .put("recordId", "record-1")
        .put("directives", JSONArray().apply { directives.forEach { put(it) } })

    private fun directive(namespace: String, name: String, payload: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("header", JSONObject().put("namespace", namespace).put("name", name)).put("payload", payload)
}

package io.github.pkczc.eta.hook.breeno

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        assertEquals(raw, BreenoNativeMessage.filter(raw, agentTurnClaimed = false))
    }

    @Test
    fun etaInjectedFramesBypassNativeAnswerFiltering() {
        val raw = message(
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 2).put("content", "ETA 的回答")),
            directive("SpeechSynthesizer", "Speak", JSONObject().put("text", "ETA 的回答")),
        )
            .put("extend", JSONObject().put("etaAgent", true)).toString()

        assertEquals(raw, filter(raw))
        assertTrue(BreenoNativeMessage.isEtaInjected(raw))
    }

    @Test
    fun claimedTurnDoesNotNeedRoomMetadataToSuppressNativeOutput() {
        val json = message(
            directive("SpeechSynthesizer", "Speak"),
            directive("App", "Launch"),
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 2)),
        ).apply { remove("roomId") }

        assertNull(filter(json.toString()))
        assertEquals(json.toString(), BreenoNativeMessage.filter(json.toString(), agentTurnClaimed = false))
    }

    @Test
    fun mismatchedPayloadRoomCannotBypassTurnOwnership() {
        val json = message(
            directive("System", "SetVolume", JSONObject().put("roomId", "room-2")),
            directive("System", "SetVolume"),
        )
        assertNull(filter(json.toString()))
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
    fun nullRoomMetadataCannotBypassTurnOwnership() {
        val raw = message(directive("System", "SetVolume")).put("roomId", JSONObject.NULL).toString()

        assertNull(filter(raw))
        assertEquals(raw, BreenoNativeMessage.filter(raw, agentTurnClaimed = false))
    }

    @Test
    fun emptyAndOversizedPacketsKeepTheirExistingErrorBoundary() {
        val tooLarge = "x".repeat(BreenoNativeMessage.MAX_CONTENT_CHARS + 1)

        assertEquals(tooLarge, filter(tooLarge))
        assertEquals("{}", filter("{}"))
        assertEquals("", filter(""))
        assertNull(filter(null))
    }

    @Test
    fun dispatchPolicyKeepsRecognitionInputAndLifecycleButBlocksNativeOutputs() {
        listOf("PhoneManager", "SystemStorage", "System", "SystemScreen", "App", "Sms",
            "PhoneCall", "Ocr", "AnalogClick", "Recommend", "SpeechSynthesizer").forEach { namespace ->
            assertTrue(namespace, BreenoNativeMessage.suppresses(namespace, "Execute", null))
        }
        assertTrue(BreenoNativeMessage.suppresses("MyAI", "StreamTextCard", 2))
        assertTrue(BreenoNativeMessage.suppresses("MyAI", "StreamTextCard", 0))
        assertTrue(BreenoNativeMessage.suppresses("MyAI", "LoadingStateCard", null))
        assertTrue(BreenoNativeMessage.suppresses("Tracking", "BreenoFeedback", null))
        assertFalse(BreenoNativeMessage.suppresses("MyAI", "StreamTextCard", 1))
        assertFalse(BreenoNativeMessage.suppresses("SpeechRecognizer", "RecognizeResult", null))
        assertFalse(BreenoNativeMessage.suppresses("Session", "Finish", null))
        assertFalse(BreenoNativeMessage.isEtaInjected(null))
        assertFalse(BreenoNativeMessage.isEtaInjected(message().toString()))
    }

    @Test
    fun nativeModeDoesNotParseOrSuppressUnrelatedPackets() {
        assertEquals("not JSON", BreenoNativeMessage.filter("not JSON", agentTurnClaimed = false))
    }

    @Test(expected = JSONException::class)
    fun malformedJsonReachesTheHookErrorBoundary() {
        filter("not JSON")
    }

    private fun filter(raw: String?): String? =
        BreenoNativeMessage.filter(raw, agentTurnClaimed = true)

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

package io.github.mangi.eta.hook.breeno

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoNativeMessageTest {
    @Test
    fun voiceQueryClaimsBeforeAnEarlierActionInTheSameBatch() {
        val frame = parse(
            directive("System", "SetVolume"),
            directive("SpeechRecognizer", "RecognizeResult", JSONObject().put("isFinal", true).put("text", "调低音量")),
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 1).put("content", "调低音量")),
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 2).put("content", "已调低音量")),
            directive("SpeechSynthesizer", "Speak"),
        )
        val claimed = mutableSetOf<String>()
        var submissions = 0
        frame.queries().forEach {
            submissions++
            claimed += it.roomId
        }
        val kept = frame.retainedIndices { it in claimed }

        assertEquals(1, submissions)
        assertEquals(listOf(1, 2), kept)
        val result = JSONObject(requireNotNull(frame.filteredContent(kept)))
        assertEquals("record-1", result.getString("recordId"))
        assertEquals(2, result.getJSONArray("directives").length())
    }

    @Test
    fun anUnclaimedOrRejectedQueryKeepsNativeProcessing() {
        val frame = parse(query("native request"), directive("System", "SetVolume"))

        assertEquals(listOf(0, 1), frame.retainedIndices { false })
    }

    @Test
    fun finalizedQueryCardWinsOverAsrAndRetainsItsImageReferences() {
        val frame = parse(
            directive("SpeechRecognizer", "RecognizeResult", JSONObject().put("isFinal", true).put("text", "识别原文")),
            query("最终提问").apply { getJSONObject("payload").put("imageUrl", "content://image/1") },
        )

        val query = frame.queries().single()
        assertEquals("最终提问", query.text)
        assertEquals("content://image/1", JSONObject(requireNotNull(query.queryPayload)).getString("imageUrl"))
    }

    @Test
    fun claimedTextRequestsSuppressActionsWithoutAnIncomingQueryCard() {
        val frame = parse(directive("App", "Launch"), directive("SpeechSynthesizer", "Speak"))

        val kept = frame.retainedIndices { it == "room-1" }

        assertTrue(kept.isEmpty())
        assertNull(frame.filteredContent(kept))
    }

    @Test
    fun newNativeReplyAndActionTypesAreAlsoSuppressed() {
        val frame = parse(
            directive("MyAI", "RichAnswerCard"),
            directive("Alarm", "Create"),
            directive("Media", "Play"),
            directive("SpeechRecognizer", "StopCapture"),
            directive("Session", "Finish"),
            directive("SpeechRecognizer", "ExpectSpeech"),
        )

        assertEquals(listOf(3, 4), frame.retainedIndices { true })
    }

    @Test
    fun etaInjectedFramesKeepTheirAnswerAndNeverStartAnotherRequest() {
        val json = message(query("eta query"), directive("MyAI", "StreamTextCard", JSONObject().put("type", 2)))
            .put("extend", JSONObject().put("etaAgent", "true"))
        val frame = requireNotNull(BreenoNativeMessage.parse(json.toString()))

        assertTrue(frame.isInjected)
        assertTrue(frame.queries().isEmpty())
        assertEquals(listOf(0, 1), frame.retainedIndices { true })
    }

    @Test
    fun payloadRoomTakesPrecedenceAndOtherRoomsRemainNative() {
        val frame = parse(
            directive("System", "SetVolume", JSONObject().put("roomId", "room-2")),
            directive("System", "SetVolume"),
        )

        assertEquals(listOf(0), frame.retainedIndices { it == "room-1" })
    }

    @Test
    fun payloadRoomCanIdentifyAFrameWithoutTopLevelRoom() {
        val json = message(query("hello").apply { getJSONObject("payload").put("roomId", "payload-room") })
            .removeRoom()
        val frame = requireNotNull(BreenoNativeMessage.parse(json.toString(), "current-room"))

        assertEquals("payload-room", frame.queries().single().roomId)
    }

    @Test
    fun localDispatchCanUseCurrentRoomWhenMetadataIsMissing() {
        val json = message(query("hello"), directive("System", "SetVolume")).removeRoom()
        val frame = requireNotNull(BreenoNativeMessage.parse(json.toString(), "current-room"))

        assertEquals("current-room", frame.queries().single().roomId)
        assertEquals(listOf(0), frame.retainedIndices { it == "current-room" })
    }

    @Test
    fun missingRoomNeverClaimsOrGloballySuppresses() {
        val json = message(query("hello"), directive("System", "SetVolume")).removeRoom()
        val frame = requireNotNull(BreenoNativeMessage.parse(json.toString()))

        assertTrue(frame.queries().isEmpty())
        assertEquals(listOf(0, 1), frame.retainedIndices { true })
    }

    @Test
    fun partialRecognitionAndPartialQueryCardsDoNotStartEta() {
        val frame = parse(
            directive("SpeechRecognizer", "RecognizeResult", JSONObject().put("isFinal", false).put("text", "partial")),
            query("partial").apply { getJSONObject("payload").put("isFinal", false) },
        )

        assertFalse(frame.isInjected)
        assertTrue(frame.queries().isEmpty())
    }

    @Test
    fun missingFinalFlagAndNullQueryContentDoNotStartEta() {
        val frame = parse(
            directive("SpeechRecognizer", "RecognizeResult", JSONObject().put("text", "partial")),
            directive("MyAI", "StreamTextCard", JSONObject().put("type", 1).put("content", JSONObject.NULL)),
        )

        assertTrue(frame.queries().isEmpty())
    }

    @Test
    fun explicitPartialFlagWinsOverAnAlternativeFinalField() {
        val frame = parse(directive(
            "SpeechRecognizer", "RecognizeResult",
            JSONObject().put("isFinal", false).put("final", true).put("text", "partial"),
        ))

        assertTrue(frame.queries().isEmpty())
    }

    @Test
    fun nullRoomMetadataDoesNotBecomeALiteralNullRoom() {
        val json = message(query("hello"))
            .put("roomId", JSONObject.NULL)
        val frame = requireNotNull(BreenoNativeMessage.parse(json.toString()))

        assertTrue(frame.queries().isEmpty())
        assertEquals(listOf(0), frame.retainedIndices { true })
    }

    @Test
    fun finalRecognitionResultsAreCombinedWithoutUsingPartialText() {
        val results = JSONArray().put(JSONObject().put("text", "打开")).put(JSONObject().put("text", "设置"))
        val frame = parse(directive("SpeechRecognizer", "RecognizeResult", JSONObject().put("isFinal", true).put("results", results)))

        val query = frame.queries().single()
        assertEquals("打开设置", query.text)
        assertEquals("record-1", query.recordId)
        assertEquals("original-1", query.originalRecordId)
        assertEquals("session-1", query.sessionId)
        assertNull(query.queryPayload)
    }

    @Test
    fun invalidAndOversizedFramesRemainUnrecognized() {
        assertNull(BreenoNativeMessage.parse("not JSON"))
        assertNull(BreenoNativeMessage.parse("{}"))
        assertNull(BreenoNativeMessage.parse("x".repeat(BreenoNativeMessage.MAX_CONTENT_CHARS + 1)))
    }

    private fun parse(vararg directives: JSONObject): BreenoNativeMessage =
        requireNotNull(BreenoNativeMessage.parse(message(*directives).toString()))

    private fun message(vararg directives: JSONObject): JSONObject = JSONObject()
        .put("roomId", "room-1")
        .put("recordId", "record-1")
        .put("originalRecordId", "original-1")
        .put("sessionId", "session-1")
        .put("directives", JSONArray().apply { directives.forEach { put(it) } })

    private fun JSONObject.removeRoom(): JSONObject = apply { remove("roomId") }

    private fun query(text: String): JSONObject =
        directive("MyAI", "StreamTextCard", JSONObject().put("type", 1).put("content", text))

    private fun directive(namespace: String, name: String, payload: JSONObject = JSONObject()): JSONObject =
        JSONObject()
            .put("header", JSONObject().put("namespace", namespace).put("name", name))
            .put("payload", payload)
}

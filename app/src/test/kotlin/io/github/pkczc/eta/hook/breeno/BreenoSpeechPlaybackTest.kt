package io.github.pkczc.eta.hook.breeno

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import io.github.pkczc.eta.core.AgentLogger
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BreenoSpeechPlaybackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val warnings = mutableListOf<String>()
    private val logger = object : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) { warnings += message }
        override fun error(message: String, throwable: Throwable?) = Unit
    }
    private val playback by lazy { BreenoSpeechPlayback(context, logger) }

    @Before
    fun languageAvailable() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault())
    }

    @After
    fun close() {
        playback.stop()
        idle()
    }

    @Test
    fun finalEtaAnswerIsReadWithoutMarkdownOrCodeAndReleasesTheEngine() {
        val reader = start("这是 **ETA 的回答**。\n\n```sh\nnative_command\n```")

        reader.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()

        assertEquals("这是 ETA 的回答。", reader.spokenTextList.joinToString("").trim())
        assertTrue(reader.isShutdown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun newInputStopsPlaybackAndIgnoresLateInitialization() {
        val reader = start("上一轮 ETA 回答")

        playback.stop()
        idle()
        reader.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()

        assertTrue(reader.spokenTextList.isEmpty())
        assertTrue(reader.isStopped)
        assertTrue(reader.isShutdown)
    }

    @Test
    fun releasedTurnCannotStartOrResumeSpeechWhileItsCallbacksAreQueued() {
        var owned = true
        playback.speak("old", "旧回答") { owned }
        owned = false
        idle()
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())

        owned = true
        playback.speak("new", "新回答") { owned }
        idle()
        val reader = shadowOf(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        owned = false
        reader.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertTrue(reader.spokenTextList.isEmpty())
        assertTrue(reader.isShutdown)
    }

    @Test
    fun supersededInitializationCannotReadAnOldAnswerOrCloseTheNewReader() {
        val old = start("旧回答")
        val current = start("新回答")

        old.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertFalse(current.isShutdown)
        current.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()

        assertTrue(old.spokenTextList.isEmpty())
        assertEquals(listOf("新回答"), current.spokenTextList)
        assertTrue(current.isShutdown)
    }

    @Test
    fun failedInitializationDoesNotReadAndReleasesTheEngine() {
        val reader = start("ETA 回答")

        reader.onInitListener.onInit(TextToSpeech.ERROR)
        idle()

        assertTrue(reader.spokenTextList.isEmpty())
        assertTrue(reader.isShutdown)
        assertTrue(warnings.single().contains("initialization_failed"))
    }

    @Test
    fun initializationTimeoutReleasesTheEngineAndIgnoresLateSuccess() {
        val reader = start("ETA 回答")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        reader.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()

        assertTrue(reader.isShutdown)
        assertTrue(reader.spokenTextList.isEmpty())
        assertTrue(warnings.single().contains("initialization_timeout"))
    }

    @Test
    fun longAnswerIsQueuedInOrderWithoutSplittingSurrogatePairs() {
        val text = "你好😀".repeat(1_500)
        val reader = start(text)

        reader.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()

        assertEquals(text, reader.spokenTextList.joinToString(""))
        assertTrue(reader.spokenTextList.all {
            it.length < TextToSpeech.getMaxSpeechInputLength() &&
                !it.first().isLowSurrogate() && !it.last().isHighSurrogate()
        })
        assertTrue(reader.isShutdown)
    }

    @Test
    fun deniedAudioFocusDoesNotStartPlayback() {
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val reader = start("ETA 回答")

        reader.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()

        assertTrue(reader.spokenTextList.isEmpty())
        assertTrue(reader.isShutdown)
        assertEquals(1, warnings.size)
    }

    private fun start(text: String): ShadowTextToSpeech {
        playback.speak("run", text) { true }
        idle()
        return shadowOf(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
}

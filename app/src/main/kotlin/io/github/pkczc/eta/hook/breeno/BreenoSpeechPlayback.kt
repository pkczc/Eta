package io.github.pkczc.eta.hook.breeno

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.pkczc.eta.agent.voice.SpeechAudioLease
import io.github.pkczc.eta.agent.voice.SpeechText
import io.github.pkczc.eta.core.AgentLogger
import io.github.pkczc.eta.core.safeLogType
import java.util.Locale

/** ETA 的最终回答使用独立的系统 TTS 客户端，不依赖被过滤的小布原生回答。 */
internal class BreenoSpeechPlayback(
    private val context: Context,
    private val logger: AgentLogger,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var lease: SpeechAudioLease? = null
    private var generation = 0L
    private var initTimeout: Runnable? = null

    fun speak(runId: String, content: String, isCurrent: () -> Boolean) {
        handler.post {
            if (!isCurrent()) return@post
            stopOnMain()
            if (content.isBlank()) return@post
            if (content.length > MAX_CONTENT_CHARS) {
                logger.warn("Breeno ETA speech skipped: answer_too_long")
                return@post
            }
            val session = generation
            runCatching {
                val readable = SpeechText.readable(content)
                if (readable.isBlank()) return@runCatching
                val chunks = SpeechText.chunks(readable, minOf(500, TextToSpeech.getMaxSpeechInputLength() - 1))
                val lastUtterance = "$runId:${chunks.lastIndex}"
                initTimeout = Runnable { fail(session, "initialization_timeout") }.also {
                    handler.postDelayed(it, INIT_TIMEOUT_MS)
                }
                engine = TextToSpeech(context) { status ->
                    handler.post initialized@{
                        if (session != generation) return@initialized
                        if (!isCurrent()) {
                            stopOnMain()
                            return@initialized
                        }
                        initTimeout?.let(handler::removeCallbacks)
                        initTimeout = null
                        if (status != TextToSpeech.SUCCESS) {
                            fail(session, "initialization_failed")
                            return@initialized
                        }
                        runCatching playback@{
                            val player = engine ?: return@playback
                            check(player.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE)
                            player.setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            player.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) = Unit

                                override fun onDone(utteranceId: String?) {
                                    if (utteranceId == lastUtterance) handler.post {
                                        if (session == generation) stopOnMain()
                                    }
                                }

                                @Deprecated("Required by the platform listener")
                                override fun onError(utteranceId: String?) {
                                    handler.post { fail(session, "playback_failed") }
                                }

                                override fun onError(utteranceId: String?, errorCode: Int) {
                                    handler.post { fail(session, "playback_failed_$errorCode") }
                                }
                            })
                            lease = SpeechAudioLease(context, interruptOnScreenOff = false, interrupt = ::stop)
                            lease?.acquire(playback = true)
                            chunks.forEachIndexed { index, text ->
                                check(player.speak(text,
                                    if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                                    null, "$runId:$index") == TextToSpeech.SUCCESS)
                            }
                        }.onFailure { fail(session, it.safeLogType()) }
                    }
                }
            }.onFailure { fail(session, it.safeLogType()) }
        }
    }

    fun stop() {
        handler.post { stopOnMain() }
    }

    private fun fail(session: Long, reason: String) {
        if (session != generation) return
        logger.warn("Breeno ETA speech unavailable: $reason")
        stopOnMain()
    }

    private fun stopOnMain() {
        generation++
        initTimeout?.let(handler::removeCallbacks)
        initTimeout = null
        val previous = engine
        engine = null
        runCatching { previous?.stop() }
        runCatching { previous?.shutdown() }
        runCatching { lease?.close() }
        lease = null
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 15_000L
        const val MAX_CONTENT_CHARS = 30_000
    }
}

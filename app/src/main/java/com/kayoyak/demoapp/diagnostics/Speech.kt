package com.kayoyak.demoapp.diagnostics

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Reading a reply out loud.
 *
 * ## Why it is offline
 *
 * It uses whatever engine the device already has. That matters here for the same
 * reason everything else does: the app's contract is that the network is for
 * downloading models and nothing else, and a speech engine that quietly uploads
 * what it is asked to say would break it without touching this app's code. The
 * engine is chosen by the user in system settings and this app does not override
 * it.
 *
 * ## What it will not do
 *
 * It will not speak if the device has no Chinese voice. Half of this app's replies
 * are Chinese, and reading them with an English voice is not reading them out; it
 * is a noise a user would reasonably read as the feature being broken.
 *
 * ## Lifecycle
 *
 * `TextToSpeech` holds a service connection and native resources, and the platform
 * documentation is explicit that `shutdown()` is required rather than optional. One
 * instance is held here for the app's life and released when the container goes
 * away -- not one per spoken reply, because speaking is something a user does
 * repeatedly, and a service bind per press is visible as a delay.
 */
class Speech(private val context: Context) {

    /** Each utterance needs an id; the listener uses them to know what finished. */
    private val counter = AtomicLong(0)

    private var engine: TextToSpeech? = null
    private var speaking = false

    /** What the last call to [speak] decided, for the screen to act on. */
    private var onFinished: (() -> Unit)? = null

    /** True once the engine has started and reported a usable Chinese voice. */
    var available: Boolean = false
        private set

    /** True while something is being read. */
    val isSpeaking: Boolean get() = speaking

    /**
     * Starts the engine. Safe to call more than once.
     *
     * [onAvailability] is called on the main thread once the answer is known, which
     * is after this returns -- the platform connects to a service to find out.
     */
    fun start(onAvailability: (Boolean) -> Unit = {}) {
        if (engine != null) {
            onAvailability(available)
            return
        }
        engine = TextToSpeech(context) { status ->
            val handle = engine
            if (handle == null || status != TextToSpeech.SUCCESS) {
                available = false
                onAvailability(false)
                return@TextToSpeech
            }
            val chinese = handle.setLanguage(Locale.CHINA)
            available = chinese != TextToSpeech.LANG_MISSING_DATA &&
                chinese != TextToSpeech.LANG_NOT_SUPPORTED
            // A slower rate than the default. The engine's default is tuned for
            // short interface strings; a paragraph of reasoning read at that speed
            // is unlistenable.
            handle.setSpeechRate(0.9f)
            handle.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        // Only the last piece ends the reading. Anything else would
                        // clear the state while the next piece is still queued.
                        if (utteranceId == lastQueued) {
                            speaking = false
                            val done = onFinished
                            onFinished = null
                            done?.invoke()
                        }
                    }

                    @Deprecated("Required by the interface; the platform calls it.")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                        val done = onFinished
                        onFinished = null
                        done?.invoke()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        speaking = false
                        val done = onFinished
                        onFinished = null
                        done?.invoke()
                    }
                },
            )
            onAvailability(available)
        }
    }

    /** The id of the last piece queued, which is the one whose end means "finished". */
    private var lastQueued: String? = null

    /**
     * Reads [text], replacing whatever is being read.
     *
     * [onDone] runs when the last piece finishes, and when the engine reports an
     * error -- a screen that stayed "reading" after a failure would be one the user
     * cannot get out of.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val handle = engine
        if (handle == null || !available) {
            onDone()
            return
        }

        val pieces = SpeechChunker.split(text, TextToSpeech.getMaxSpeechInputLength())
        if (pieces.isEmpty()) {
            onDone()
            return
        }

        // Whatever was being read stops. Pressing read on one reply and hearing the
        // one above it continue is not what the press meant.
        handle.stop()

        onFinished = onDone
        speaking = true
        pieces.forEachIndexed { index, piece ->
            val id = "null-${counter.incrementAndGet()}"
            if (index == pieces.lastIndex) lastQueued = id
            // QUEUE_ADD after the flush above, so the pieces play in order and the
            // engine does not cut each one off as the next arrives.
            handle.speak(piece, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    /** Stops reading. */
    fun stop() {
        engine?.stop()
        speaking = false
        onFinished = null
        lastQueued = null
    }

    /** Releases the service connection. The platform requires this. */
    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        speaking = false
        available = false
    }
}

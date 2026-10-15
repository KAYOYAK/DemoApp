package com.example.demoapp.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading a reply out loud, on a device that has a speech engine.
 *
 * Why this is instrumented: `TextToSpeech` is a platform service reached through a
 * binder, and a JVM unit test has no engine to reach -- the same reason the
 * encrypted-storage test lives here. What it can prove is that the app's own path
 * through the platform completes: the availability callback arrives, a queued
 * reading ends, and a reading that cannot happen still ends rather than leaving the
 * button stuck. What it cannot prove is whether the words *sound* right, or that
 * anything was audible at all -- a voice with no data behind it reports an error,
 * the app clears its state as designed, and this test stays green. Judge the audio
 * by listening; judge the path by this file.
 *
 * With no Chinese voice installed the reading test reports SKIPPED and passes, the
 * way the model tests do, so a run on a bare emulator is not a broken build. Read
 * the log rather than trusting a green result.
 */
@RunWith(AndroidJUnit4::class)
class SpeechTest {

    /** Binding the engine and asking it about a language crosses a service connection. */
    private val startTimeoutMs = 30_000L

    /** A short sentence, synthesized. Generous, because a network voice is not instant. */
    private val readTimeoutMs = 60_000L

    @Test
    fun aChineseSentenceIsReadToTheEnd(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val speech = Speech(instrumentation.targetContext)
        val availability = CompletableDeferred<Boolean>()
        instrumentation.runOnMainSync { speech.start { availability.complete(it) } }
        val voice = withTimeout(startTimeoutMs) { availability.await() }

        if (!voice) {
            println("SPEECH: SKIPPED - this device reports no Chinese voice, so the read-aloud button stays hidden")
            instrumentation.runOnMainSync { speech.shutdown() }
            return@runBlocking
        }

        val finished = CompletableDeferred<Unit>()
        instrumentation.runOnMainSync { speech.speak("这是一次朗读验证。") { finished.complete(Unit) } }
        val queued = speech.isSpeaking
        withTimeout(readTimeoutMs) { finished.await() }
        println("SPEECH: available=true queued=$queued finished=true")
        assertFalse(
            "The end of the last piece is the end of the reading",
            speech.isSpeaking,
        )
        instrumentation.runOnMainSync { speech.shutdown() }
    }

    @Test
    fun aReadingThatCannotHappenStillEnds(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val speech = Speech(instrumentation.targetContext)
        val availability = CompletableDeferred<Boolean>()
        instrumentation.runOnMainSync { speech.start { availability.complete(it) } }
        val voice = withTimeout(startTimeoutMs) { availability.await() }

        if (voice) {
            println("SPEECH: this device has a Chinese voice, so the no-voice path is not exercised here")
            instrumentation.runOnMainSync { speech.shutdown() }
            return@runBlocking
        }

        var finished = false
        instrumentation.runOnMainSync { speech.speak("这是一句话。") { finished = true } }
        println("SPEECH: available=false finished=$finished")
        assertTrue(
            "A read that cannot happen has to end, or the button that started it never recovers",
            finished,
        )
        assertFalse("Nothing may be reported as being read", speech.isSpeaking)
        instrumentation.runOnMainSync { speech.shutdown() }
    }
}

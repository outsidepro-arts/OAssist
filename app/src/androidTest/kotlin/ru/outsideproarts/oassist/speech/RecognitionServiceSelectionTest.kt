package ru.outsideproarts.oassist.speech

import android.Manifest
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.RecognitionServiceCatalog
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.VoiceListener
import ru.outsideproarts.oassist.vis.OAssistRecognitionService

@RunWith(AndroidJUnit4::class)
class RecognitionServiceSelectionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val fixtureComponent = ComponentName(testContext, RecognitionServiceFixture::class.java)

    @Test
    fun catalogListsFixtureAndExcludesOAssistStub() {
        val catalog = catalog()

        val available = catalog.availableServices()
        assertTrue(available.any { it.componentName == fixtureComponent.flattenToString() })
        assertFalse(
            available.any {
                it.componentName == ComponentName(targetContext, OAssistRecognitionService::class.java).flattenToString()
            },
        )
        assertEquals(fixtureComponent, catalog.resolveComponent(fixtureComponent.flattenToString()))
        assertNull(catalog.resolveComponent("removed.test/removed.test.Service"))
    }

    @Test
    fun voiceListenerBindsExplicitlySelectedComponentAndReturnsTranscript() {
        val automation = instrumentation.uiAutomation
        val listener = sharedVoiceListener()
        val finalText = AtomicReference<String?>()
        val sttError = AtomicReference<SttError?>()
        val complete = CountDownLatch(1)

        try {
            automation.grantRuntimePermission(targetContext.packageName, Manifest.permission.RECORD_AUDIO)
            automation.grantRuntimePermission(testContext.packageName, Manifest.permission.RECORD_AUDIO)
            val startError = AtomicReference<CaptureError?>()
            instrumentation.runOnMainSync {
                startError.set(
                    listener.startStt(
                        language = "ru-RU",
                        recognizerComponent = fixtureComponent.flattenToString(),
                        listener = object : SttListener {
                            override fun onPartial(text: String) = Unit
                            override fun onFinal(text: String) {
                                finalText.set(text)
                                complete.countDown()
                            }
                            override fun onSttError(error: SttError) {
                                sttError.set(error)
                                complete.countDown()
                            }
                        },
                    ),
                )
            }

            assertNull(startError.get())
            assertTrue("RecognitionService callback timed out", complete.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertNull("RecognitionService failed", sttError.get())
            assertEquals("fixture:ru-RU", finalText.get())
        } finally {
            instrumentation.runOnMainSync { listener.cancelStt() }
        }
    }

    @Test
    fun voiceListenerRecognizesRepeatedlyWithSelectedService() {
        val automation = instrumentation.uiAutomation
        val listener = sharedVoiceListener()

        try {
            automation.grantRuntimePermission(targetContext.packageName, Manifest.permission.RECORD_AUDIO)
            automation.grantRuntimePermission(testContext.packageName, Manifest.permission.RECORD_AUDIO)

            repeat(20) { index ->
                val language = "marker-$index"
                val finalTexts = java.util.ArrayList<String>()
                val sttErrors = java.util.ArrayList<SttError>()
                val complete = CountDownLatch(1)
                val startError = AtomicReference<CaptureError?>()

                instrumentation.runOnMainSync {
                    startError.set(
                        listener.startStt(
                            language = language,
                            recognizerComponent = fixtureComponent.flattenToString(),
                            listener = object : SttListener {
                                override fun onPartial(text: String) = Unit

                                override fun onFinal(text: String) {
                                    finalTexts.add(text)
                                    complete.countDown()
                                }

                                override fun onSttError(error: SttError) {
                                    sttErrors.add(error)
                                    complete.countDown()
                                }
                            },
                        ),
                    )
                }

                assertNull("start failed on recognition $index", startError.get())
                assertTrue(
                    "RecognitionService callback timed out on recognition $index",
                    complete.await(10, TimeUnit.SECONDS),
                )
                instrumentation.waitForIdleSync()
                assertTrue("RecognitionService failed on recognition $index: $sttErrors", sttErrors.isEmpty())
                assertEquals(listOf("fixture:$language"), finalTexts)
                assertEquals(CaptureState.Idle, listener.state.value)
            }
        } finally {
            instrumentation.runOnMainSync { listener.cancelStt() }
        }
    }

    @Test
    fun voiceListenerCanRestartAfterCancelAndRetryBusyRecognition() {
        val automation = instrumentation.uiAutomation
        val listener = sharedVoiceListener()
        val sttErrors = java.util.ArrayList<SttError>()

        try {
            automation.grantRuntimePermission(targetContext.packageName, Manifest.permission.RECORD_AUDIO)
            automation.grantRuntimePermission(testContext.packageName, Manifest.permission.RECORD_AUDIO)

            val cancelledFinals = java.util.ArrayList<String>()
            val cancellationReady = CountDownLatch(1)
            instrumentation.runOnMainSync {
                assertNull(
                    listener.startStt(
                        language = "delay:cancelled",
                        recognizerComponent = fixtureComponent.flattenToString(),
                        listener = object : SttListener {
                            override fun onPartial(text: String) { cancellationReady.countDown() }
                            override fun onFinal(text: String) {
                                cancelledFinals.add(text)
                            }
                            override fun onSttError(error: SttError) {
                                sttErrors.add(error)
                            }
                        },
                    ),
                )
            }

            assertTrue("Delayed recognition did not start", cancellationReady.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { listener.cancelStt() }
            assertEquals(CaptureState.Idle, listener.state.value)

            val restartFinals = java.util.ArrayList<String>()
            val restartComplete = CountDownLatch(1)
            val restartStartError = AtomicReference<CaptureError?>()
            instrumentation.runOnMainSync {
                restartStartError.set(
                    listener.startStt(
                        language = "cancel-restart",
                        recognizerComponent = fixtureComponent.flattenToString(),
                        listener = object : SttListener {
                            override fun onPartial(text: String) = Unit
                            override fun onFinal(text: String) {
                                restartFinals.add(text)
                                restartComplete.countDown()
                            }
                            override fun onSttError(error: SttError) {
                                sttErrors.add(error)
                                restartComplete.countDown()
                            }
                        },
                    ),
                )
            }

            assertNull(restartStartError.get())
            assertTrue("RecognitionService callback after cancellation timed out", restartComplete.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue("Cancelled recognition returned a transcript", cancelledFinals.isEmpty())
            assertEquals(listOf("fixture:cancel-restart"), restartFinals)
            assertTrue("RecognitionService failed after cancellation: $sttErrors", sttErrors.isEmpty())
            assertEquals(CaptureState.Idle, listener.state.value)

            val retryLanguage = "retry-once:retry-marker"
            val retryFinals = java.util.ArrayList<String>()
            val retryComplete = CountDownLatch(1)
            val retryStartError = AtomicReference<CaptureError?>()
            instrumentation.runOnMainSync {
                retryStartError.set(
                    listener.startStt(
                        language = retryLanguage,
                        recognizerComponent = fixtureComponent.flattenToString(),
                        listener = object : SttListener {
                            override fun onPartial(text: String) = Unit
                            override fun onFinal(text: String) {
                                retryFinals.add(text)
                                retryComplete.countDown()
                            }
                            override fun onSttError(error: SttError) {
                                sttErrors.add(error)
                                retryComplete.countDown()
                            }
                        },
                    ),
                )
            }

            assertNull(retryStartError.get())
            assertTrue("RecognitionService retry callback timed out", retryComplete.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals(listOf("fixture:$retryLanguage"), retryFinals)
            assertTrue("RecognitionService retry failed: $sttErrors", sttErrors.isEmpty())
            assertEquals(CaptureState.Idle, listener.state.value)
        } finally {
            instrumentation.runOnMainSync { listener.cancelStt() }
        }
    }

    @Test
    fun voiceListenerCanStartNextRecognitionFromFinalCallback() {
        val listener = sharedVoiceListener()
        val finals = java.util.ArrayList<String>()
        val errors = java.util.ArrayList<SttError>()
        val startFailure = AtomicReference<CaptureError?>()
        val complete = CountDownLatch(1)
        val callback = object : SttListener {
            override fun onPartial(text: String) = Unit

            override fun onFinal(text: String) {
                finals.add(text)
                if (finals.size == 5) {
                    complete.countDown()
                } else {
                    startFailure.set(
                        listener.startStt(
                            language = "callback-${finals.size}",
                            listener = this,
                            recognizerComponent = fixtureComponent.flattenToString(),
                        ),
                    )
                    if (startFailure.get() != null) complete.countDown()
                }
            }

            override fun onSttError(error: SttError) {
                errors.add(error)
                complete.countDown()
            }
        }

        try {
            instrumentation.uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.RECORD_AUDIO)
            instrumentation.uiAutomation.grantRuntimePermission(testContext.packageName, Manifest.permission.RECORD_AUDIO)
            instrumentation.runOnMainSync {
                startFailure.set(listener.startStt("callback-0", callback, fixtureComponent.flattenToString()))
            }

            assertTrue("Recognition chain timed out", complete.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertNull("Starting from onFinal failed", startFailure.get())
            assertTrue("Recognition chain failed: $errors", errors.isEmpty())
            assertEquals((0 until 5).map { "fixture:callback-$it" }, finals)
            assertEquals(CaptureState.Idle, listener.state.value)
        } finally {
            instrumentation.runOnMainSync { listener.cancelStt() }
        }
    }

    @Test
    fun defaultSelectionFollowsSystemChangesAndExplicitAlias() {
        val listener = sharedVoiceListener()
        val original = android.provider.Settings.Secure.getString(targetContext.contentResolver, "voice_recognition_service")
        val alternate = ComponentName(testContext, AlternateRecognitionServiceFixture::class.java)
        grantMicrophonePermissions()

        try {
            setSystemRecognizer(fixtureComponent.flattenToString())
            assertEquals("fixture:default-a", recognize(listener, "default-a", null))
            assertEquals("fixture:explicit-a", recognize(listener, "explicit-a", fixtureComponent.flattenToString()))
            assertEquals("fixture:default-a-again", recognize(listener, "default-a-again", null))
            setSystemRecognizer(alternate.flattenToString())
            assertEquals("fixture:alternate:default-b", recognize(listener, "default-b", null))
            setSystemRecognizer(fixtureComponent.flattenToString())
            assertEquals("fixture:default-a-restored", recognize(listener, "default-a-restored", null))
        } finally {
            setSystemRecognizer(original)
            instrumentation.runOnMainSync { listener.cancelStt() }
        }
    }

    @Test
    fun lateResultFromCancelledCaptureDoesNotReachNextListener() {
        val listener = sharedVoiceListener()
        val cancelledFinals = java.util.ArrayList<String>()
        val nextFinals = java.util.ArrayList<String>()
        val errors = java.util.ArrayList<SttError>()
        val cancelledReady = CountDownLatch(1)
        val lateResultEmitted = AtomicReference(false)
        val nextReady = CountDownLatch(1)
        val complete = CountDownLatch(1)
        grantMicrophonePermissions()

        try {
            recognize(listener, "warmup", fixtureComponent.flattenToString())
            instrumentation.runOnMainSync {
                assertNull(listener.startStt("late:cancelled", object : SttListener {
                    override fun onPartial(text: String) { cancelledReady.countDown() }
                    override fun onFinal(text: String) { cancelledFinals.add(text) }
                    override fun onSttError(error: SttError) { errors.add(error) }
                }, fixtureComponent.flattenToString()))
            }
            assertTrue("First recognition did not start", cancelledReady.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                listener.cancelStt()
                assertNull(listener.startStt("delay:cancelled", object : SttListener {
                    override fun onPartial(text: String) {
                        lateResultEmitted.set(text == "fixture:late-delivered")
                        nextReady.countDown()
                    }
                    override fun onFinal(text: String) {
                        nextFinals.add(text)
                        complete.countDown()
                    }
                    override fun onSttError(error: SttError) {
                        errors.add(error)
                        complete.countDown()
                    }
                }, fixtureComponent.flattenToString()))
            }
            assertTrue("Next recognition did not become ready", nextReady.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue("Fixture did not emit the old result", lateResultEmitted.get())
            assertTrue("Cancelled capture delivered a final", cancelledFinals.isEmpty())
            assertTrue("Old final reached the next listener: $nextFinals", nextFinals.isEmpty())
            instrumentation.runOnMainSync { listener.stopStt() }
            assertTrue("Next final timed out", complete.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue("Recognition failed: $errors", errors.isEmpty())
            assertEquals(listOf("fixture:delay:cancelled"), nextFinals)
            assertEquals(CaptureState.Idle, listener.state.value)
        } finally {
            instrumentation.runOnMainSync { listener.cancelStt() }
        }
    }

    private fun recognize(listener: VoiceListener, language: String, component: String?): String? {
        val text = AtomicReference<String?>()
        val error = AtomicReference<SttError?>()
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync {
            assertNull(listener.startStt(language, object : SttListener {
                override fun onPartial(text: String) = Unit
                override fun onFinal(value: String) {
                    text.set(value)
                    complete.countDown()
                }
                override fun onSttError(value: SttError) {
                    error.set(value)
                    complete.countDown()
                }
            }, component))
        }
        assertTrue("Recognition timed out: $language", complete.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertNull("Recognition failed: $language", error.get())
        return text.get()
    }

    private fun grantMicrophonePermissions() {
        instrumentation.uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(testContext.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun setSystemRecognizer(component: String?) {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(android.provider.Settings.Secure.putString(
                targetContext.contentResolver, "voice_recognition_service", component,
            ))
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun catalog() = RecognitionServiceCatalog(
        targetContext,
        excludedComponents = setOf(ComponentName(targetContext, OAssistRecognitionService::class.java)),
    )

    private fun sharedVoiceListener(): VoiceListener =
        sharedListener ?: VoiceListener(targetContext, catalog()).also { sharedListener = it }

    companion object {
        private var sharedListener: VoiceListener? = null

        @AfterClass
        @JvmStatic
        fun releaseVoiceListener() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { sharedListener?.close() }
            sharedListener = null
        }
    }
}

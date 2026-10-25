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
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.speech.CaptureError
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
        val listener = VoiceListener(targetContext, catalog())
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
            assertNull("RecognitionService failed", sttError.get())
            assertEquals("fixture:ru-RU", finalText.get())
        } finally {
            instrumentation.runOnMainSync { listener.cancelStt() }
        }
    }

    private fun catalog() = RecognitionServiceCatalog(
        targetContext,
        excludedComponents = setOf(ComponentName(targetContext, OAssistRecognitionService::class.java)),
    )
}

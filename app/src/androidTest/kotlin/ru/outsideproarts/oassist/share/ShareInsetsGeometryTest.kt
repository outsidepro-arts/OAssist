package ru.outsideproarts.oassist.share

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.data.chat.*
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.feature.share.ShareActivity
import ru.outsideproarts.oassist.feature.share.R as ShareR
import ru.outsideproarts.oassist.core.ui.R as UiR
import java.io.File

class ShareInsetsGeometryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyboardSetting = "show_ime_with_hard_keyboard"

    private fun setHardwareKeyboardIme(value: String?) {
        val command = if (value == null) "settings delete secure $keyboardSetting"
            else "settings put secure $keyboardSetting $value"
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            if (Settings.Secure.getString(context.contentResolver, keyboardSetting) == value) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Failed to set $keyboardSetting to $value; command output: $output")
    }

    @Test fun rootsAndKeyboardStayInsideWindowInsets() {
        val originalKeyboardSetting = Settings.Secure.getString(context.contentResolver, keyboardSetting)
        require(originalKeyboardSetting == null || originalKeyboardSetting == "0" || originalKeyboardSetting == "1") {
            "Unexpected $keyboardSetting value: $originalKeyboardSetting"
        }
        val oldFactory = AgentFactory.get()
        val oldRepository = checkNotNull(ChatHandle.get())
        val oldNetwork = checkNotNull(NetworkHandle.get())
        val db = Room.inMemoryDatabaseBuilder(context, OAssistDatabase::class.java).build()
        val media = File(context.cacheDir, "share-insets-${System.nanoTime()}")
        val factory = object : AgentFactory by oldFactory {
            override suspend fun modeConfig(mode: AssistMode) = ModeConfig(mode, modelId = "test", soundEnabled = false, autoSaveChats = false)
            override suspend fun providerForMode(mode: AssistMode): ProviderProfile? = null
            override fun usageTotals() = MutableStateFlow(UsageTotalsState())
            override fun resetUsageTotals() = Unit
            override fun clearSessionApprovals(mode: AssistMode) = Unit
            override suspend fun recordModelUsage(usage: ProviderUsage) = Unit
        }
        try {
            setHardwareKeyboardIme("1")
            AgentFactory.register(factory)
            ChatHandle.register(ChatRepository(db, ChatMediaStore(media)))
            NetworkHandle.register(object : NetworkHandle { override val isOnline = MutableStateFlow(false) })
            val intent = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Shared content")
            ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
                fun rect(label: String, description: Boolean = false): Rect {
                    val node = if (description) compose.onNodeWithContentDescription(label) else compose.onNodeWithText(label)
                    node.assertIsDisplayed()
                    return node.fetchSemanticsNode().boundsInWindow
                }
                fun checkBounds(name: String, bounds: Rect, ime: Boolean = false) {
                    scenario.onActivity { activity ->
                        val view = activity.window.decorView
                        val insets = checkNotNull(view.rootWindowInsets)
                        val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or (if (ime) WindowInsets.Type.ime() else 0)
                        val safe = insets.getInsets(types)
                        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                        val metrics = activity.windowManager.currentWindowMetrics.bounds
                        Log.i("ShareInsetsGeometry", "$name bounds=$bounds decorOrigin=${loc.toList()} window=$metrics safe=$safe imeVisible=${insets.isVisible(WindowInsets.Type.ime())}")
                        assertTrue("$name has positive size: $bounds", bounds.width > 0 && bounds.height > 0)
                        assertTrue("$name left $bounds / $safe", bounds.left + loc[0] >= metrics.left + safe.left - 1)
                        assertTrue("$name top $bounds / $safe", bounds.top + loc[1] >= metrics.top + safe.top - 1)
                        assertTrue("$name right $bounds / $safe", bounds.right + loc[0] <= metrics.right - safe.right + 1)
                        assertTrue("$name bottom $bounds / $safe", bounds.bottom + loc[1] <= metrics.bottom - safe.bottom + 1)
                        if (ime) assertTrue("IME must be visible with nonzero inset", insets.isVisible(WindowInsets.Type.ime()) && insets.getInsets(WindowInsets.Type.ime()).bottom > insets.getInsets(WindowInsets.Type.systemBars()).bottom)
                    }
                }
                val title = context.getString(ShareR.string.share_title)
                val open = context.getString(ShareR.string.share_open_chat)
                val close = context.getString(ShareR.string.share_close)
                val newChat = context.getString(UiR.string.chats_new)
                fun scrollTo(label: String) {
                    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
                    compose.onNodeWithText(label).assertIsDisplayed()
                }
                fun scrollToClose() {
                    compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                    scrollTo(close)
                }
                compose.dismissShareKeyboard(scenario)
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                scrollTo(title)
                checkBounds("list title", rect(title))
                scrollTo(open)
                checkBounds("list open", rect(open))
                scrollToClose()
                val initialClose = rect(close)
                checkBounds("list close", initialClose)
                scrollTo(newChat)
                checkBounds("list new chat", rect(newChat))
                compose.onNodeWithText(newChat).performClick()
                val back = context.getString(ShareR.string.share_back)
                val question = context.getString(ShareR.string.share_ask_label)
                val send = context.getString(ShareR.string.share_send)
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(back).fetchSemanticsNodes().isNotEmpty() }
                compose.dismissShareKeyboard(scenario)
                checkBounds("conversation back", rect(back, true))
                val initialQuestion = rect(question)
                checkBounds("conversation question", initialQuestion)
                scenario.onActivity {
                    val safe = it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    val expected = it.windowManager.currentWindowMetrics.bounds.bottom - safe.bottom - 16 * it.resources.displayMetrics.density
                    assertTrue("question bottom gap: $initialQuestion expected=$expected", kotlin.math.abs(initialQuestion.bottom - expected) <= 3f)
                }
                val initialSend = rect(send, true)
                checkBounds("conversation send", initialSend)
                compose.onNodeWithText(question).performClick().performTextInput("geometry question")
                compose.waitUntil(10_000) {
                    var visible = false
                    scenario.onActivity { val i = it.window.decorView.rootWindowInsets; visible = i?.isVisible(WindowInsets.Type.ime()) == true && i.getInsets(WindowInsets.Type.ime()).bottom > i.getInsets(WindowInsets.Type.systemBars()).bottom }
                    visible
                }
                compose.waitUntil(10_000) {
                    val b = rect(question); var desired = 0f
                    scenario.onActivity { desired = it.windowManager.currentWindowMetrics.bounds.bottom - it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom - 16 * it.resources.displayMetrics.density }
                    kotlin.math.abs(b.bottom - desired) <= 3f
                }
                checkBounds("IME back", rect(back, true), true)
                val imeQuestion = rect(question)
                val imeSend = rect(send, true)
                checkBounds("IME question", imeQuestion, true)
                checkBounds("IME send", imeSend, true)
                assertTrue("IME clipped question: $imeQuestion vs $initialQuestion", imeQuestion.height >= initialQuestion.height - 1)
                assertTrue("IME clipped send: $imeSend vs $initialSend", imeSend.height >= initialSend.height - 1)
                compose.onNodeWithContentDescription(back).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithContentDescription(back).fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.dismissShareKeyboard(scenario)
                scrollToClose()
                compose.waitUntil(10_000) { kotlin.math.abs(rect(close).bottom - initialClose.bottom) <= 2f }
                val afterClose = rect(close)
                checkBounds("returned close", afterClose)
                assertTrue("list bottom did not return: $initialClose -> $afterClose", kotlin.math.abs(initialClose.bottom - afterClose.bottom) <= 2f)
                compose.onNodeWithText(close).performClick()
            }
            val emptyIntent = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            ActivityScenario.launch<ShareActivity>(emptyIntent).use { scenario ->
                compose.dismissShareKeyboard(scenario)
                fun scrollTo(label: String) {
                    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
                    compose.onNodeWithText(label).assertIsDisplayed()
                }
                fun checkEmpty(name: String, description: Boolean = false) {
                    val node = if (description) compose.onNodeWithContentDescription(name) else compose.onNodeWithText(name)
                    node.assertIsDisplayed()
                    val bounds = node.fetchSemanticsNode().boundsInWindow
                    scenario.onActivity {
                        val view = it.window.decorView
                        val loc = IntArray(2).also(view::getLocationOnScreen)
                        val metrics = it.windowManager.currentWindowMetrics.bounds
                        val safe = view.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        Log.i("ShareInsetsGeometry", "empty $name bounds=$bounds window=$metrics safe=$safe origin=${loc.toList()}")
                        assertTrue("empty $name invalid bounds=$bounds", bounds.width > 0 && bounds.height > 0 &&
                            bounds.left + loc[0] >= metrics.left + safe.left - 1 && bounds.top + loc[1] >= metrics.top + safe.top - 1 &&
                            bounds.right + loc[0] <= metrics.right - safe.right + 1 && bounds.bottom + loc[1] <= metrics.bottom - safe.bottom + 1)
                    }
                }
                val emptyOpen = context.getString(ShareR.string.share_open_chat)
                val emptyClose = context.getString(ShareR.string.share_close)
                val emptyNewChat = context.getString(UiR.string.chats_new)
                scrollTo(emptyOpen)
                checkEmpty(emptyOpen)
                scrollTo(emptyClose)
                checkEmpty(emptyClose)
                scrollTo(emptyNewChat)
                checkEmpty(emptyNewChat)
                compose.onNodeWithText(emptyNewChat).performClick()
                val back = context.getString(ShareR.string.share_back)
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(back).fetchSemanticsNodes().isNotEmpty() }
                compose.dismissShareKeyboard(scenario)
                checkEmpty(back, true)
                val emptyQuestion = context.getString(ShareR.string.share_ask_label)
                checkEmpty(emptyQuestion)
                val questionBounds = compose.onNodeWithText(emptyQuestion).fetchSemanticsNode().boundsInWindow
                scenario.onActivity {
                    val safe = it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    val expected = it.windowManager.currentWindowMetrics.bounds.bottom - safe.bottom - 16 * it.resources.displayMetrics.density
                    assertTrue("empty question bottom gap: $questionBounds expected=$expected", kotlin.math.abs(questionBounds.bottom - expected) <= 3f)
                }
                checkEmpty(context.getString(ShareR.string.share_send), true)
            }
        } finally {
            try {
                setHardwareKeyboardIme(originalKeyboardSetting)
            } finally {
                AgentFactory.register(oldFactory); ChatHandle.register(oldRepository); NetworkHandle.register(oldNetwork)
                db.close(); media.deleteRecursively()
            }
        }
    }
}

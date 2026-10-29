package ru.outsideproarts.oassist.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.data.diagnostics.DiagnosticStore
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.feature.settings.DiagnosticsScreen
import ru.outsideproarts.oassist.feature.settings.R

@RunWith(AndroidJUnit4::class)
class DiagnosticExportRecreationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var base: Context
    private lateinit var directory: File
    private lateinit var output: File
    private lateinit var store: DiagnosticStore
    private val registry = object : ActivityResultRegistry() {
        var launchedCode = -1
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) { launchedCode = requestCode }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    @Before fun prepare() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "diagnostic-recreation-${UUID.randomUUID()}").apply { check(mkdirs()) }
        output = File(base.cacheDir, "diagnostics_exports/recreation-${UUID.randomUUID()}.zip")
        output.parentFile!!.mkdirs()
        val isolated = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("${directory.name}-$name", mode)
        }
        store = DiagnosticStore(isolated)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "selected-root", content = "selected-private"))
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "excluded-root", content = "excluded-private"))
        store.listOperations(0)
    }

    @After fun cleanup() {
        store.close()
        output.delete()
        require(directory.canonicalFile.toPath().startsWith(base.cacheDir.canonicalFile.toPath()))
        check(directory.deleteRecursively())
        base.deleteSharedPreferences("${directory.name}-diagnostics")
    }

    @Test fun selectedSessionsSurviveRestorationWhileDocumentIsPending() {
        val restoration = showScreen()
        compose.onNodeWithText(base.getString(R.string.diagnostics_select_sessions)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("excluded", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("excluded", substring = true).performScrollTo().performClick()
        launchSave()
        restoration.emulateSavedInstanceStateRestore()
        deliverResult()
        compose.waitUntil(10_000) { output.isFile && output.length() > 0 && savedStatusVisible() }
        val events = ZipFile(output).use { zip -> zip.getInputStream(zip.getEntry("events.jsonl")).bufferedReader().readText() }
        assertTrue(events.contains("selected-private"))
        assertFalse(events.contains("excluded-private"))
    }

    @Test fun changedGenerationRejectsPendingDocumentAfterRestoration() {
        val restoration = showScreen()
        launchSave()
        restoration.emulateSavedInstanceStateRestore()
        store.clear()
        deliverResult()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(base.getString(R.string.diagnostics_error)).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(output.exists())
    }

    private fun showScreen(): StateRestorationTester = StateRestorationTester(compose).also { restoration ->
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme { DiagnosticsScreen(store, onBack = {}) }
            }
        }
    }

    private fun launchSave() {
        compose.onNodeWithText(base.getString(R.string.diagnostics_save)).performScrollTo().performClick()
        compose.onNodeWithText(base.getString(R.string.diagnostics_continue)).performClick()
        assertTrue(registry.launchedCode >= 0)
    }

    private fun deliverResult() {
        val uri: Uri = FileProvider.getUriForFile(base, "${base.packageName}.diagnostics", output)
        compose.runOnIdle { registry.dispatchResult(registry.launchedCode, uri) }
    }

    private fun savedStatusVisible() =
        compose.onAllNodesWithText(base.getString(R.string.diagnostics_saved)).fetchSemanticsNodes().isNotEmpty()
}

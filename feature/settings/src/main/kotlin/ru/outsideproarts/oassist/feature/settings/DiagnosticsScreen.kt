package ru.outsideproarts.oassist.feature.settings

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.data.diagnostics.DiagnosticStore
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticHealth
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistLiveStatus
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow
import ru.outsideproarts.oassist.core.ui.components.OAssistSwitchRow
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.WeakHashMap

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private const val MAX_EXPORT_BYTES = 256L * 1024 * 1024
private val exportMutex = Mutex()
private val exportStoreIds = WeakHashMap<DiagnosticStore, String>()
private fun exportStoreId(store: DiagnosticStore): String = synchronized(exportStoreIds) {
    exportStoreIds.getOrPut(store) { UUID.randomUUID().toString() }
}

private enum class ExportAction { SAVE, SHARE }

private data class ExportRequest(
    val since: Long,
    val includeMedia: Boolean,
    val sessions: Set<String>?,
    val generation: Long,
    val processId: String,
)

private fun ExportRequest.save(): Bundle = Bundle().apply {
    putLong("since", since)
    putBoolean("media", includeMedia)
    putStringArrayList("sessions", sessions?.let { ArrayList(it) })
    putLong("generation", generation)
    putString("process", processId)
}

private fun restoreExportRequest(saved: Bundle?): ExportRequest? {
    if (saved == null || !saved.containsKey("since") || !saved.containsKey("media") ||
        !saved.containsKey("sessions") || !saved.containsKey("generation") ||
        saved.getString("process") == null
    ) return null
    return ExportRequest(
        saved.getLong("since"), saved.getBoolean("media"),
        saved.getStringArrayList("sessions")?.toSet(), saved.getLong("generation"),
        saved.getString("process")!!,
    )
}

@Composable
fun DiagnosticsScreen(store: DiagnosticStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var health by remember(store) { mutableStateOf(DiagnosticHealth(false, false, 0, 0)) }
    var includeWeek by remember { mutableStateOf(false) }
    var includeMedia by remember { mutableStateOf(false) }
    var selectSessions by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(emptyList<DiagnosticStore.OperationSummary>()) }
    var selectedSessions by remember { mutableStateOf(emptySet<String>()) }
    var loadingSessions by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<ExportAction?>(null) }
    var pendingSave by rememberSaveable { mutableStateOf<Bundle?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.diagnostics_saved)
    val clearedMessage = stringResource(R.string.diagnostics_cleared)
    val errorMessage = stringResource(R.string.diagnostics_error)
    val shareTitle = stringResource(R.string.diagnostics_share_title)
    val onText = stringResource(R.string.state_enabled)
    val offText = stringResource(R.string.state_disabled)

    LaunchedEffect(context, store) {
        health = withContext(Dispatchers.IO) {
            exportMutex.withLock { cleanExports(context, expiredOnly = true) }
            store.health()
        }
    }

    fun since(): Long = System.currentTimeMillis() - DAY_MILLIS * if (includeWeek) 7 else 1

    fun request(): ExportRequest = ExportRequest(
        since(), includeMedia, if (selectSessions) selectedSessions.toSet() else null,
        store.generationToken(), exportStoreId(store),
    )

    fun ensureCurrent(request: ExportRequest) {
        if (request.processId != exportStoreId(store) || store.generationToken() != request.generation) {
            throw IOException("Diagnostic data changed")
        }
    }

    LaunchedEffect(store, includeWeek) {
        loadingSessions = true
        try {
            val found = withContext(Dispatchers.IO) { store.listOperations(since()) }
            sessions = found
            selectedSessions = found.mapTo(mutableSetOf()) { it.operationId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = errorMessage
            statusError = true
        } finally {
            loadingSessions = false
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        val snapshot = restoreExportRequest(pendingSave)
        pendingSave = null
        if (uri != null) {
            busy = true
            status = ""
            scope.launch {
                var opened = false
                try {
                    withContext(Dispatchers.IO) {
                        exportMutex.withLock {
                            if (snapshot == null) throw IOException("Missing export request")
                            ensureCurrent(snapshot)
                            val stream = context.contentResolver.openOutputStream(uri)
                                ?: throw IOException("Document unavailable")
                            opened = true
                            stream.use { store.export(LimitedOutputStream(it), snapshot.since, snapshot.includeMedia, snapshot.sessions) }
                            ensureCurrent(snapshot)
                        }
                    }
                    status = savedMessage
                    statusError = false
                } catch (e: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (opened) runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                    throw e
                } catch (_: Exception) {
                    if (opened) runCatching { context.contentResolver.delete(uri, null, null) }
                    status = errorMessage
                    statusError = true
                } finally {
                    busy = false
                    health = withContext(Dispatchers.IO) { store.health() }
                }
            }
        }
    }

    fun share(snapshot: ExportRequest) {
        busy = true
        status = ""
        scope.launch {
            var createdFile: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    exportMutex.withLock {
                        ensureCurrent(snapshot)
                        cleanExports(context, expiredOnly = false)
                        val dir = exportDirectory(context)
                        check(dir.isDirectory || dir.mkdirs())
                        val partial = File(dir, "${UUID.randomUUID()}.partial")
                        val ready = File(dir, "${UUID.randomUUID()}.zip")
                        try {
                            partial.outputStream().use {
                                store.export(LimitedOutputStream(it), snapshot.since, snapshot.includeMedia, snapshot.sessions)
                            }
                            ensureCurrent(snapshot)
                            if (!partial.renameTo(ready)) throw IOException("Cannot publish report")
                            createdFile = ready
                            ready
                        } finally {
                            partial.delete()
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                try {
                    ensureCurrent(snapshot)
                } catch (e: IOException) {
                    file.delete()
                    throw e
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, shareTitle))
                statusError = false
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    createdFile?.delete()
                }
                throw e
            } catch (_: Exception) {
                status = errorMessage
                statusError = true
            } finally {
                busy = false
                health = withContext(Dispatchers.IO) { store.health() }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(OAssistDimens.ScreenContentPadding),
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing),
    ) {
        Text(
            stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().oassistHeading(),
        )
        OAssistPanel(title = stringResource(R.string.diagnostics_title)) {
            Text(stringResource(R.string.diagnostics_explanation))
            OAssistSwitchRow(
                checked = health.contentEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        busy = true
                        try {
                            health = withContext(Dispatchers.IO) {
                                store.setContentEnabled(enabled)
                                store.health()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            status = errorMessage
                            statusError = true
                        } finally {
                            busy = false
                        }
                    }
                },
                label = stringResource(R.string.diagnostics_record),
                stateLabelOn = onText,
                stateLabelOff = offText,
                enabled = !busy,
            )
            Text(stringResource(R.string.diagnostics_storage, Formatter.formatFileSize(context, health.storedBytes), health.droppedEvents))
            if (!health.available) {
                OAssistLiveStatus(stringResource(R.string.diagnostics_unavailable), assertive = true)
            }
            OAssistSwitchRow(
                checked = includeWeek,
                onCheckedChange = { includeWeek = it },
                label = stringResource(R.string.diagnostics_week),
                stateLabelOn = onText,
                stateLabelOff = offText,
                enabled = !busy,
            )
            OAssistSwitchRow(
                checked = includeMedia,
                onCheckedChange = { includeMedia = it },
                label = stringResource(R.string.diagnostics_media),
                stateLabelOn = onText,
                stateLabelOff = offText,
                enabled = !busy,
            )
            OAssistSwitchRow(
                checked = selectSessions,
                onCheckedChange = { selectSessions = it },
                label = stringResource(R.string.diagnostics_select_sessions),
                stateLabelOn = onText,
                stateLabelOff = offText,
                enabled = !busy,
            )
            if (selectSessions) {
                if (sessions.isEmpty()) Text(stringResource(R.string.diagnostics_no_sessions))
                sessions.forEach { session ->
                    val mode = when (session.mode) {
                        "CHAT" -> stringResource(R.string.mode_chat)
                        "ASSISTANT" -> stringResource(R.string.mode_assistant)
                        "SHARE" -> stringResource(R.string.mode_share)
                        else -> stringResource(R.string.diagnostics_other_operation)
                    }
                    val time = remember(session.timestampMillis) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(session.timestampMillis))
                    }
                    OAssistSwitchRow(
                        checked = session.operationId in selectedSessions,
                        onCheckedChange = { selected ->
                            selectedSessions = if (selected) selectedSessions + session.operationId
                            else selectedSessions - session.operationId
                        },
                        label = stringResource(R.string.diagnostics_session, mode, time, session.operationId.take(8)),
                        stateLabelOn = onText,
                        stateLabelOff = offText,
                        enabled = !busy,
                    )
                }
            }
            OAssistSettingRow(
                title = stringResource(R.string.diagnostics_save),
                onClick = if (busy || pendingSave != null || (selectSessions && loadingSessions)) null else { { pendingAction = ExportAction.SAVE } },
            )
            OAssistSettingRow(
                title = stringResource(R.string.diagnostics_share),
                onClick = if (busy || pendingSave != null || (selectSessions && loadingSessions)) null else { { pendingAction = ExportAction.SHARE } },
            )
            OAssistSettingRow(
                title = stringResource(R.string.diagnostics_clear),
                onClick = if (busy) null else { { confirmClear = true } },
            )
            if (busy) OAssistLiveStatus(stringResource(R.string.diagnostics_busy))
            OAssistLiveStatus(status, assertive = statusError)
            OAssistSettingRow(title = stringResource(R.string.action_back), onClick = onBack)
        }
    }

    if (pendingAction != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.diagnostics_confirm_title)) },
            text = { Text(stringResource(R.string.diagnostics_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingAction
                    pendingAction = null
                    when (action) {
                        ExportAction.SAVE -> {
                            pendingSave = request().save()
                            try {
                                saveLauncher.launch("oassist-diagnostics.zip")
                            } catch (_: Exception) {
                                pendingSave = null
                                status = errorMessage
                                statusError = true
                            }
                        }
                        ExportAction.SHARE -> share(request())
                        null -> Unit
                    }
                }) { Text(stringResource(R.string.diagnostics_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diagnostics_clear)) },
            text = { Text(stringResource(R.string.diagnostics_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                exportMutex.withLock {
                                    store.clear()
                                    cleanExports(context, expiredOnly = false)
                                }
                            }
                            status = clearedMessage
                            statusError = false
                            sessions = emptyList()
                            selectedSessions = emptySet()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            status = errorMessage
                            statusError = true
                        } finally {
                            busy = false
                            health = withContext(Dispatchers.IO) { store.health() }
                        }
                    }
                }) { Text(stringResource(R.string.diagnostics_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun exportDirectory(context: Context) = File(context.cacheDir, "diagnostics_exports")

private fun cleanExports(context: Context, expiredOnly: Boolean) {
    val dir = exportDirectory(context)
    val cutoff = System.currentTimeMillis() - DAY_MILLIS
    dir.listFiles()?.filter { it.isFile && (!expiredOnly || it.lastModified() < cutoff) }?.forEach { file ->
        if (file.extension == "zip") {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        file.delete()
    }
}

private class LimitedOutputStream(output: OutputStream) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
        reserve(1)
        out.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        reserve(length)
        out.write(bytes, offset, length)
    }

    private fun reserve(size: Int) {
        if (written + size > MAX_EXPORT_BYTES) throw IOException("Diagnostic export exceeds size limit")
        written += size
    }
}

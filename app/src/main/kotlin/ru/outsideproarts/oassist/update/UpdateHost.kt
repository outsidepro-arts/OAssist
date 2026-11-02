package ru.outsideproarts.oassist.update

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.feature.settings.UpdatesScreen
import ru.outsideproarts.oassist.feature.settings.R as SettingsR

@Composable
fun UpdateHost(manager: UpdateManager, onOpenUpdates: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var resumed by remember { mutableStateOf(false) }
    var showInstallPermission by rememberSaveable { mutableStateOf(false) }
    val navigate by rememberUpdatedState(onOpenUpdates)
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) manager.installationError()
    }
    fun install() {
        scope.launch {
            handOffUpdate(
                prepare = manager::prepareInstall,
                current = manager::isInstallCurrent,
                launch = { prepared ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", prepared.file)
                    @Suppress("DEPRECATION")
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    intent.clipData = android.content.ClipData.newRawUri("", uri)
                    installer.launch(intent)
                },
                interrupted = { if (manager.state.value.phase == UpdatePhase.READY) manager.markInstallRequested(true) },
                failed = manager::installationError,
            )
        }
    }
    val sources = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else manager.installationError()
    }
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed = true
                manager.setForeground(true)
                val activity = context as? Activity
                if (activity?.intent?.getBooleanExtra("open_updates", false) == true) {
                    activity.intent.removeExtra("open_updates")
                    navigate()
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                resumed = false
                manager.setForeground(false)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); manager.setForeground(false) }
    }
    LaunchedEffect(state.phase, resumed, state.installAttempt) {
        if (state.phase == UpdatePhase.READY && resumed && manager.consumeInstallRequested()) {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else showInstallPermission = true
        }
    }
    if (showInstallPermission) {
        AlertDialog(
            onDismissRequest = { showInstallPermission = false },
            title = { Text(stringResource(SettingsR.string.update_install_permission_title)) },
            text = { Text(stringResource(SettingsR.string.update_install_permission_body)) },
            confirmButton = { TextButton(onClick = {
                showInstallPermission = false
                try { sources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                catch (_: Exception) { manager.installationError() }
            }) { Text(stringResource(SettingsR.string.update_open_settings)) } },
            dismissButton = { TextButton(onClick = { showInstallPermission = false }) { Text(stringResource(SettingsR.string.update_later)) } },
        )
    }
    if (state.prompt && state.candidate != null && resumed) {
        val candidate = state.candidate!!
        AlertDialog(
            onDismissRequest = manager::dismissPrompt,
            title = { Text(stringResource(SettingsR.string.update_available)) },
            text = { Text(stringResource(SettingsR.string.update_candidate, candidate.manifest.versionName,
                candidate.manifest.versionCode, candidate.manifest.apkSize / (1024.0 * 1024.0))) },
            confirmButton = { TextButton(onClick = {
                manager.dismissPrompt()
                navigate()
                manager.markInstallRequested(true)
                manager.download()
            }) { Text(stringResource(SettingsR.string.update_download_install)) } },
            dismissButton = { TextButton(onClick = manager::dismissPrompt) { Text(stringResource(SettingsR.string.update_later)) } },
        )
    }
}

@Composable
fun UpdatesRoute(manager: UpdateManager, onBack: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val preferences = state.preferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notificationsAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    if (preferences == null) {
        androidx.compose.foundation.layout.Column {
            TextButton(onClick = onBack) { Text(stringResource(SettingsR.string.update_back)) }
            ru.outsideproarts.oassist.core.ui.components.OAssistLiveStatus(
                stringResource(if (state.failure == UpdateFailure.SETTINGS) SettingsR.string.update_settings_error else SettingsR.string.update_checking))
            if (state.failure == UpdateFailure.SETTINGS) {
                TextButton(onClick = manager::start) { Text(stringResource(SettingsR.string.update_retry)) }
            }
        }
        return
    }
    val status = when (state.phase) {
        UpdatePhase.IDLE -> ""
        UpdatePhase.CHECKING -> stringResource(SettingsR.string.update_checking)
        UpdatePhase.AVAILABLE -> stringResource(SettingsR.string.update_available)
        UpdatePhase.CURRENT -> stringResource(SettingsR.string.update_current)
        UpdatePhase.DOWNLOADING -> stringResource(SettingsR.string.update_downloading)
        UpdatePhase.READY -> stringResource(SettingsR.string.update_ready)
        UpdatePhase.ERROR -> stringResource(when (state.failure) {
            UpdateFailure.DOWNLOAD -> SettingsR.string.update_download_error
            UpdateFailure.INSTALL -> SettingsR.string.update_install_error
            UpdateFailure.SETTINGS -> SettingsR.string.update_settings_error
            else -> SettingsR.string.update_check_error
        })
    }
    UpdatesScreen(
        preferences, manager.installed(), state.candidate, status,
        busy = state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.DOWNLOADING,
        downloading = state.phase == UpdatePhase.DOWNLOADING, progress = state.progress,
        notificationsAllowed = notificationsAllowed,
        onAutomaticChange = manager::setAutomaticChecks,
        onNotificationsChange = { enabled ->
            manager.setNotifications(enabled)
            if (enabled && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onChannelChange = manager::setChannel,
        onCheck = { scope.launch { manager.check(manual = true) } },
        onDownload = { manager.markInstallRequested(true); manager.download() },
        onCancelDownload = manager::cancelDownload,
        onNotificationSettings = {
            try { notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            catch (_: Exception) { manager.installationError() }
        },
        onBack = onBack,
    )
}

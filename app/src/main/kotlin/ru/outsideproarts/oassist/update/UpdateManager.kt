package ru.outsideproarts.oassist.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.outsideproarts.oassist.BuildConfig
import ru.outsideproarts.oassist.MainActivity
import ru.outsideproarts.oassist.core.data.update.UpdatePreferencesDataStore
import ru.outsideproarts.oassist.core.model.update.*
import ru.outsideproarts.oassist.core.net.update.GitHubUpdateTransport
import ru.outsideproarts.oassist.feature.settings.R as SettingsR

class UpdateManager(private val context: Context, private val transport: GitHubUpdateTransport) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = UpdatePreferencesDataStore(context)
    private val cache = context.getSharedPreferences("update_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val coordinator = UpdateCheckCoordinator(transport::candidateFlow, transport::isDescendant)
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var started = false
    private var foreground = false
    private var downloadJob: Job? = null
    private var installRequested = false
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }
    val downloadedApk get() = state.value.candidate?.let { File(directory, it.manifest.apkName) }

    fun installed(): InstalledBuild {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
        return InstalledBuild(context.packageName, info.longVersionCode, info.versionName.orEmpty(), BuildConfig.DEBUG,
            BuildConfig.UPDATE_COMMIT_SHA, signer?.let(::sha256).orEmpty(), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                settings.preferences.collect { preferences ->
                    val previous = state.value.preferences
                    val channel = preferences.effectiveChannel(installed())
                    val changed = previous == null || previous.effectiveChannel(installed()) != channel ||
                        previous.automaticChecks != preferences.automaticChecks
                    if (changed) {
                        coordinator.invalidate()
                        downloadJob?.cancel()
                        installRequested = false
                        val cached = readCached(channel)
                        mutableState.value = UpdateState(preferences, cached,
                            if (cached == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE)
                    } else mutableState.update { it.copy(preferences = preferences) }
                    UpdateScheduler.configure(context, preferences, channel)
                    if (!preferences.automaticChecks || !preferences.notifications) cancelNotification()
                    if (foreground && preferences.automaticChecks) {
                        showCachedPrompt()
                        scope.launch { check(manual = false) }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                coordinator.invalidate()
                downloadJob?.cancel()
                installRequested = false
                started = false
                mutableState.value = UpdateState(phase = UpdatePhase.ERROR, failure = UpdateFailure.SETTINGS)
                runCatching { UpdateScheduler.configure(context, UpdatePreferences(automaticChecks = false), UpdateChannel.STABLE) }
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            start()
            cancelNotification()
            showCachedPrompt()
            scope.launch { check(manual = false) }
        }
    }

    private fun showCachedPrompt() {
        val current = state.value
        if (current.preferences?.automaticChecks == true && current.candidate != null &&
            cache.getLong("dismissed", -1) != current.candidate.manifest.versionCode &&
            current.phase != UpdatePhase.DOWNLOADING && current.phase != UpdatePhase.READY) {
            mutableState.update { it.copy(prompt = true) }
        }
    }

    suspend fun check(manual: Boolean) = withContext(Dispatchers.Main.immediate) {
        val preferences = state.awaitUpdatePreferences() ?: return@withContext
        if (!manual && !preferences.automaticChecks) return@withContext
        if (state.value.phase == UpdatePhase.CHECKING || state.value.phase == UpdatePhase.DOWNLOADING) return@withContext
        if (!manual && state.value.phase == UpdatePhase.READY) return@withContext
        val installed = installed()
        val channel = preferences.effectiveChannel(installed)
        val last = cache.getLong("last_${channel.name}", 0)
        val now = System.currentTimeMillis()
        if (!manual && last in 1..now && now - last < channel.checkIntervalMillis) return@withContext
        val token = coordinator.token()
        mutableState.update { it.copy(phase = UpdatePhase.CHECKING, failure = null) }
        try {
            when (val result = coordinator.find(installed, channel, token)) {
                is UpdateCheckCoordinator.Result.Available -> {
                    if (!coordinator.isCurrent(token)) return@withContext
                    cache.edit().putString("candidate", json.encodeToString(result.candidate))
                        .putString("candidate_channel", channel.name).putLong("last_${channel.name}", now).apply()
                    mutableState.update { it.copy(candidate = result.candidate, phase = UpdatePhase.AVAILABLE, failure = null) }
                    if (!manual && preferences.automaticChecks) {
                        if (foreground) showCachedPrompt()
                        else if (state.value.preferences?.notifications == true) notifyAvailable(result.candidate)
                    }
                }
                UpdateCheckCoordinator.Result.NoUpdate -> {
                    cache.edit().remove("candidate").putLong("last_${channel.name}", now).apply()
                    mutableState.update { it.copy(candidate = null, phase = UpdatePhase.CURRENT, prompt = false, failure = null) }
                    cancelNotification()
                }
                UpdateCheckCoordinator.Result.Obsolete -> Unit
            }
        } catch (cancel: CancellationException) {
            if (coordinator.isCurrent(token)) mutableState.update { it.copy(phase = if (it.candidate == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE) }
            throw cancel
        } catch (_: Exception) {
            if (coordinator.isCurrent(token)) mutableState.update { it.copy(phase = UpdatePhase.ERROR, failure = UpdateFailure.CHECK) }
        }
    }

    fun setAutomaticChecks(enabled: Boolean) = save { settings.setAutomaticChecks(enabled) }
    fun setNotifications(enabled: Boolean) = save { settings.setNotifications(enabled) }
    fun setChannel(channel: UpdateChannel) = save { settings.setChannel(channel) }
    private fun save(action: suspend () -> Unit) { scope.launch {
        try { action() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { mutableState.update { it.copy(phase = UpdatePhase.ERROR, failure = UpdateFailure.SETTINGS) } }
    } }

    fun dismissPrompt() {
        state.value.candidate?.let { cache.edit().putLong("dismissed", it.manifest.versionCode).apply() }
        mutableState.update { it.copy(prompt = false) }
    }

    fun download() {
        if (downloadJob?.isActive == true) return
        val candidate = state.value.candidate ?: return
        val preferences = state.value.preferences ?: return
        if (UpdateSelector.eligibleCandidates(listOf(candidate), installed(), preferences.effectiveChannel(installed())).isEmpty()) return
        // A cached prompt can be accepted while a refresh is suspended in the network.
        // That refresh must never replace the APK currently being downloaded.
        coordinator.invalidate()
        val token = coordinator.token()
        mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, prompt = false, failure = null) }
        downloadJob = scope.launch {
            try {
                val file = File(directory, candidate.manifest.apkName)
                if (!file.exists() || !withContext(Dispatchers.IO) { verifyApk(file, candidate) }) {
                    transport.download(candidate, file) { bytes, total ->
                        val progress = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
                        mutableState.update { if (coordinator.isCurrent(token)) it.copy(progress = progress) else it }
                    }
                }
                check(withContext(Dispatchers.IO) { verifyApk(file, candidate) })
                if (coordinator.isCurrent(token)) mutableState.update { it.copy(phase = UpdatePhase.READY, progress = 100) }
            } catch (cancel: CancellationException) {
                if (coordinator.isCurrent(token)) mutableState.update { it.copy(phase = UpdatePhase.AVAILABLE) }
                throw cancel
            } catch (_: Exception) {
                if (coordinator.isCurrent(token)) mutableState.update { it.copy(phase = UpdatePhase.ERROR, failure = UpdateFailure.DOWNLOAD) }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }
    suspend fun prepareInstall(): PreparedUpdate? = withContext(Dispatchers.IO) {
        state.awaitUpdatePreferences() ?: return@withContext null
        val token = coordinator.token()
        val candidate = state.value.candidate ?: return@withContext null
        val file = File(directory, candidate.manifest.apkName)
        if (verifyApk(file, candidate)) PreparedUpdate(file, token) else null
    }
    fun isInstallCurrent(prepared: PreparedUpdate) = coordinator.isCurrent(prepared.generation) &&
        state.value.candidate?.manifest?.apkName == prepared.file.name

    private fun verifyApk(file: File, candidate: UpdateCandidate): Boolean {
        if (!file.isFile || file.length() != candidate.manifest.apkSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        if (digest.digest().hex() != candidate.manifest.apkSha256.lowercase()) return false
        val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        val current = installed()
        val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray() ?: return false
        return info.packageName == current.applicationId && info.longVersionCode == candidate.manifest.versionCode &&
            info.longVersionCode > current.versionCode && info.versionName == candidate.manifest.versionName &&
            info.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true &&
            sha256(signer).equals(current.certificateSha256, true)
    }

    fun installationError() { mutableState.update { it.copy(phase = UpdatePhase.ERROR, failure = UpdateFailure.INSTALL) } }
    fun markInstallRequested(value: Boolean) {
        installRequested = value
        if (value) mutableState.update { it.copy(installAttempt = it.installAttempt + 1) }
    }
    fun consumeInstallRequested(): Boolean = installRequested.also { installRequested = false }

    private fun readCached(channel: UpdateChannel): UpdateCandidate? = runCatching {
        if (cache.getString("candidate_channel", null) != channel.name) return@runCatching null
        val candidate = json.decodeFromString<UpdateCandidate>(cache.getString("candidate", null) ?: return@runCatching null)
        UpdateSelector.eligibleCandidates(listOf(candidate), installed(), channel).firstOrNull()
    }.getOrNull()

    @Suppress("MissingPermission")
    private fun notifyAvailable(candidate: UpdateCandidate) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            cache.getLong("notified", -1) == candidate.manifest.versionCode) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(SettingsR.string.update_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("open_updates", true)
        val pending = PendingIntent.getActivity(context, 7301, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(SettingsR.string.update_available))
            .setContentText(context.getString(SettingsR.string.update_notification_body, candidate.manifest.versionName))
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(NOTIFICATION_ID, notification)
        cache.edit().putLong("notified", candidate.manifest.versionCode).apply()
    }

    private fun cancelNotification() { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    fun close() { scope.cancel(); transport.close() }
    companion object {
        private const val CHANNEL_ID = "application_updates"
        private const val NOTIFICATION_ID = 7301
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }
}

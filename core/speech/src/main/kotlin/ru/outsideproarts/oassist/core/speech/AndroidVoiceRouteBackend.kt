package ru.outsideproarts.oassist.core.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import ru.outsideproarts.oassist.core.model.OAssistLog

/** BT-вход (микрофон гарнитуры) для prefer-recorder; null — гарнитуры нет. */
internal fun Context.bluetoothInputDevice(): AudioDeviceInfo? =
    getSystemService(AudioManager::class.java)
        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

/**
 * Маршрут записи на AudioManager: BT-гарнитура через `setCommunicationDevice`
 * на время захвата. Контракт API: передаётся **sink** (выходное) устройство из
 * `getAvailableCommunicationDevices()` — соответствующий источник (микрофон
 * гарнитуры) платформа выбирает сама; передача input-устройства отклоняется.
 * Действует и на вывод — на время SCO-записи звук уходит в гарнитуру в низком
 * качестве; запись короткая (PTT/hold), компромисс осознанный.
 */
class AndroidVoiceRouteBackend(context: Context) : VoiceRouteBackend {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val permissionFlag = SharedPrefsBtPermissionFlag(appContext)

    @Volatile
    private var lossCallback: AudioDeviceCallback? = null

    override fun hasBluetoothInput(): Boolean = findBluetoothSink() != null

    override fun hasBluetoothPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    // Проверки разрешений сделаны в hasBluetoothPermission(); MODIFY_AUDIO_SETTINGS — normal.
    @SuppressLint("MissingPermission")
    override fun setBluetoothRoute(): Boolean {
        val sink = findBluetoothSink() ?: return false
        val manager = audioManager ?: return false
        val accepted = runCatching { manager.setCommunicationDevice(sink) }.getOrDefault(false)
        val active = runCatching { manager.communicationDevice }.getOrNull()
        OAssistLog.info(
            TAG,
            "setCommunicationDevice sink=${sink.type} accepted=$accepted active=${active?.type}",
        )
        return accepted
    }

    @SuppressLint("MissingPermission")
    override fun clearRoute() {
        runCatching { audioManager?.clearCommunicationDevice() }
    }

    override fun watchDeviceLoss(onLost: () -> Unit) {
        unwatchDeviceLoss()
        val manager = audioManager ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = Unit

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (findBluetoothSink() == null) onLost()
            }
        }
        lossCallback = callback
        runCatching {
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    override fun unwatchDeviceLoss() {
        val callback = lossCallback ?: return
        lossCallback = null
        runCatching { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    /**
     * BT-гарнитура среди устройств связи (sink-роль). Только headset-профили:
     * A2DP-колонки — вывод без микрофона, для записи не годятся.
     */
    @SuppressLint("MissingPermission")
    private fun findBluetoothSink(): AudioDeviceInfo? =
        audioManager?.getAvailableCommunicationDevices()?.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    private companion object {
        const val TAG = "VoiceRoute"
    }
}

/**
 * Однократность системного запроса BLUETOOTH_CONNECT: флаг живёт на устройстве,
 * не в конфиге пользователя. Отказ пользователя не превращается в повторный вопрос.
 */
class SharedPrefsBtPermissionFlag(context: Context) : BtPermissionFlag {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isRequested(): Boolean = prefs.getBoolean(KEY_REQUESTED, false)

    override fun markRequested() {
        prefs.edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "voice_input"
        const val KEY_REQUESTED = "bt_permission_requested"
    }
}

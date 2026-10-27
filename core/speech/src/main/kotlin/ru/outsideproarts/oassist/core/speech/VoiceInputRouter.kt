package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Аудиомаршрут записи текущего захвата. */
enum class VoiceInputRoute {
    MICROPHONE,
    BLUETOOTH,
}

/** Что делать с BT-входом перед стартом захвата. */
enum class BtInputDecision {
    /** Гарнитура подключена и разрешение есть — поднимаем маршрут. */
    USE_BLUETOOTH,

    /** Пишем с микрофона: выключено, нет гарнитуры или разрешения. */
    USE_MIC,

    /**
     * Нужен системный запрос BLUETOOTH_CONNECT — один раз за жизнь приложения.
     * Запрашивает UI (чат/настройки); сервисный слой молча уходит в микрофон.
     */
    ASK_PERMISSION,
}

/** Android-зависимая часть маршрута записи; в JVM-тестах — фейк. */
interface VoiceRouteBackend {
    /** Есть ли подключённое устройство ввода BT (SCO/LE). */
    fun hasBluetoothInput(): Boolean

    fun hasBluetoothPermission(): Boolean

    /** true — система приняла маршрут (SCO/LE поднимается). */
    fun setBluetoothRoute(): Boolean

    fun clearRoute()

    /** Следить за исчезновением BT-входа на время активного маршрута. */
    fun watchDeviceLoss(onLost: () -> Unit)

    fun unwatchDeviceLoss()
}

/** Флаг «BLUETOOTH_CONNECT уже запрашивали»: вопрос задаётся пользователю один раз. */
interface BtPermissionFlag {
    fun isRequested(): Boolean

    fun markRequested()
}

/**
 * AUTO-маршрут записи: BT-гарнитура при подключении, иначе микрофон.
 * Чистая логика (backend инжектится), JVM-тестируема. Захват маршрута —
 * на сессию ([acquire] → [release]); потеря гарнитуры mid-capture откатывает
 * маршрут в микрофон (см. [route]).
 */
class VoiceInputRouter(
    private val backend: VoiceRouteBackend,
    private val permissionFlag: BtPermissionFlag,
    /** Диагностика маршрута; в проде — OAssistLog (см. VoiceListener), в тестах — no-op. */
    private val logInfo: (String) -> Unit = {},
) {
    private val _route = MutableStateFlow(VoiceInputRoute.MICROPHONE)
    val route: StateFlow<VoiceInputRoute> = _route.asStateFlow()

    private var activeOwner: Any? = null

    fun decide(enabled: Boolean): BtInputDecision = when {
        !enabled || !backend.hasBluetoothInput() -> BtInputDecision.USE_MIC
        backend.hasBluetoothPermission() -> BtInputDecision.USE_BLUETOOTH
        !permissionFlag.isRequested() -> BtInputDecision.ASK_PERMISSION
        else -> BtInputDecision.USE_MIC
    }

    /**
     * Захват маршрута на текущую сессию захвата; true — пишем с гарнитуры.
     * [release] обязателен в конце сессии (стоп/отмена/ошибка/dispose).
     */
    @Synchronized
    fun acquire(enabled: Boolean): Boolean {
        release()
        val decision = decide(enabled)
        if (decision != BtInputDecision.USE_BLUETOOTH) {
            logInfo("route=microphone decision=$decision")
            return false
        }
        if (!backend.setBluetoothRoute()) {
            logInfo("route=microphone setCommunicationDevice rejected")
            return false
        }
        val owner = Any()
        activeOwner = owner
        _route.value = VoiceInputRoute.BLUETOOTH
        try {
            backend.watchDeviceLoss { onDeviceLost(owner) }
        } catch (error: Exception) {
            release()
            throw error
        }
        if (activeOwner !== owner) return false
        logInfo("route=bluetooth")
        return true
    }

    /** Идемпотентно снимает маршрут; путь вызова не важен. */
    @Synchronized
    fun release() {
        if (activeOwner == null) return
        activeOwner = null
        backend.unwatchDeviceLoss()
        backend.clearRoute()
        _route.value = VoiceInputRoute.MICROPHONE
    }

    fun markPermissionRequested() = permissionFlag.markRequested()

    @Synchronized
    private fun onDeviceLost(owner: Any) {
        if (activeOwner !== owner) return
        logInfo("Bluetooth input lost mid-capture; falling back to microphone")
        release()
    }
}

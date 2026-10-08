package ru.outsideproarts.oassist.core.agent

/**
 * Запрос системных runtime-разрешений в момент действия тула (как микрофон в чате):
 * пользователь отвечает системным диалогом, агент получает результат и повторяет
 * действие («попробуйте ещё раз») либо узнаёт об отказе.
 */
interface PermissionRequester {
    /** `true` — разрешение уже выдано. */
    fun isGranted(permission: String): Boolean

    /** Запросить набор разрешений системным диалогом. */
    suspend fun request(permissions: List<String>): PermissionRequestOutcome
}

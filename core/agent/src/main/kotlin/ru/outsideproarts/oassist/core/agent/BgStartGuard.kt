package ru.outsideproarts.oassist.core.agent

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * HyperOS/MIUI: аппа 10021 («Открывать новые окна в фоне») молча режет фоновые
 * `startActivity` (result=START_ABORTED, исключения нет). Тумблеры «Другие разрешения»
 * на HyperOS 2 в эту опу не пишут — лечится только `appops set … allow`.
 */
object BgStartGuard {

    const val OP_BACKGROUND_START_ACTIVITY = 10021

    private val MIUI_MANUFACTURERS = setOf("xiaomi", "redmi", "poco")

    fun isMiui(manufacturer: String): Boolean = manufacturer.lowercase() in MIUI_MANUFACTURERS

    fun isMiui(): Boolean = isMiui(Build.MANUFACTURER)

    fun fixCommand(packageName: String): List<String> =
        listOf("appops", "set", packageName, OP_BACKGROUND_START_ACTIVITY.toString(), "allow")

    fun instruction(packageName: String): String =
        "adb shell appops set $packageName $OP_BACKGROUND_START_ACTIVITY allow"

    fun isRestrictedMode(mode: Int?): Boolean =
        mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED

    fun shouldBlock(isMiui: Boolean, isAppForeground: Boolean, mode: Int?): Boolean =
        isMiui && !isAppForeground && isRestrictedMode(mode)

    fun blockedMessage(packageName: String): String =
        "Система HyperOS/MIUI блокирует запуск приложений из фона (разрешение «Открывать новые окна в фоне»). " +
            "Включите его для OAssist или выполните: ${instruction(packageName)}, затем повторите"

    /** Опа 10021; null — reflection недоступен (fail-open). */
    fun opMode(context: Context): Int? = runCatching {
        val manager = context.getSystemService(AppOpsManager::class.java) ?: return null
        val method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        method.invoke(manager, OP_BACKGROUND_START_ACTIVITY, Process.myUid(), context.packageName) as Int
    }.getOrNull()

    /** Для UI: состояние оппы независимо от переднего плана. */
    fun isOpRestricted(context: Context): Boolean = isRestrictedMode(opMode(context))

    /** Для тулов: блокирует только фоновые старты; передний план работает и без оппы. */
    fun isBlocked(context: Context, isAppForeground: Boolean): Boolean =
        shouldBlock(isMiui(), isAppForeground, opMode(context))
}

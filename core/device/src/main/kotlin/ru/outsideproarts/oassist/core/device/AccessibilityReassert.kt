package ru.outsideproarts.oassist.core.device

/** Runs on the privileged owner, which must stay alive until cleanup finishes. */
internal class AccessibilityReassert(
    private val settle: () -> Unit = { Thread.sleep(400) },
    private val command: (List<String>) -> ExecResult,
) {
    @Synchronized
    fun run(component: String) {
        require(normalizeServiceComponent(component) == "ru.outsideproarts.oassist/ru.outsideproarts.oassist.a11y.OAssistAccessibilityService")
        val key = "enabled_accessibility_services"
        fun read(): String = command(listOf("/system/bin/settings", "get", "secure", key))
            .let { check(it.exitCode == 0) { "settings get failed: ${it.stdout}" }; it.stdout.trim().let { s -> if (s == "null") "" else s } }
        fun write(value: String) {
            val result = command(listOf("/system/bin/settings", "put", "secure", key, value))
            check(result.exitCode == 0) { "settings put failed: ${result.stdout}" }
        }
        val current = read()
        val without = removeServiceComponent(current, component)
        if (without == null) return
        try {
            write(without)
            settle()
        } finally {
            val latest = read()
            val restored = addServiceComponent(latest, component)
            if (restored != latest) write(restored)
        }
    }
}

fun normalizeServiceComponent(entry: String): String {
    val trimmed = entry.trim()
    val sep = trimmed.indexOf('/')
    if (sep <= 0 || sep == trimmed.lastIndex) return trimmed
    val pkg = trimmed.substring(0, sep)
    val cls = trimmed.substring(sep + 1)
    return "$pkg/${if (cls.startsWith('.')) pkg + cls else cls}"
}

private fun removeServiceComponent(services: String, component: String): String? {
    val parts = services.split(':').filter { it.isNotBlank() }
    val kept = parts.filter { normalizeServiceComponent(it) != normalizeServiceComponent(component) }
    return if (parts.size == kept.size) null else kept.joinToString(":")
}

private fun addServiceComponent(services: String, component: String): String {
    val parts = services.split(':').filter { it.isNotBlank() }
    return if (parts.any { normalizeServiceComponent(it) == normalizeServiceComponent(component) }) services
    else (parts + component).joinToString(":")
}

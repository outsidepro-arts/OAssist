package ru.outsideproarts.oassist.core.model

/**
 * Логгер, вырезающий секреты по паттернам и известным alias.
 * Любой другой логгер в проекте обязан идти через него.
 */
interface RedactingLogger {
    fun verbose(tag: String, message: String, throwable: Throwable? = null)
    fun debug(tag: String, message: String, throwable: Throwable? = null)
    fun info(tag: String, message: String, throwable: Throwable? = null)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: Throwable? = null, messageText: String = "")

    fun registerSecretAlias(alias: String)
    fun unregisterSecretAlias(alias: String)
}

object SecretRedactor {
    private val knownSecrets = mutableSetOf<String>()

    private val patterns: List<Regex> = listOf(
        Regex("""(?i)bearer\s+([A-Za-z0-9_\-./+=]{8,})"""),
        Regex("""(?i)basic\s+([A-Za-z0-9+/=]{8,})"""),
        Regex("""(?i)(api[_-]?key|apikey|token|secret|password|passwd|pwd|authorization|bearer)\s*[=:]\s*"?([A-Za-z0-9_\-./+=]{6,})"""),
        Regex("""sk-[A-Za-z0-9]{10,}"""),
        Regex("""sk-ant-[A-Za-z0-9\-]{10,}"""),
        Regex("""AIza[0-9A-Za-z_\-]{10,}"""),
        Regex("""xox[baprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""ghp_[A-Za-z0-9]{10,}"""),
    )

    private const val MASK = "••••"

    fun registerSecretAlias(alias: String) {
        if (alias.isNotBlank()) synchronized(knownSecrets) { knownSecrets.add(alias) }
    }

    fun unregisterSecretAlias(alias: String) {
        synchronized(knownSecrets) { knownSecrets.remove(alias) }
    }

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        val snapshot = synchronized(knownSecrets) { knownSecrets.toList() }
        for (secret in snapshot) {
            if (secret.length < 4) continue
            out = out.replace(secret, MASK)
        }
        for (pattern in patterns) {
            out = pattern.replace(out) { m ->
                if (m.groups.size >= 3 && m.groups[2] != null) {
                    val label = m.groups[1]?.value ?: "secret"
                    "$label=$MASK"
                } else {
                    MASK
                }
            }
        }
        return out
    }
}

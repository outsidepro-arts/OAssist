package ru.outsideproarts.oassist.core.agent

import android.content.ContentResolver
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SettingsAccess {
    suspend fun getSetting(namespace: String, key: String): Result<String?>
    suspend fun putSetting(namespace: String, key: String, value: String): Result<Boolean>
}

class AndroidSettingsAccess(private val resolver: ContentResolver) : SettingsAccess {
    override suspend fun getSetting(namespace: String, key: String): Result<String?> = access {
        when (namespace) {
            "system" -> Settings.System.getString(resolver, key)
            "secure" -> Settings.Secure.getString(resolver, key)
            "global" -> Settings.Global.getString(resolver, key)
            else -> error("Неизвестное пространство настроек: $namespace")
        }
    }

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Boolean> = access {
        when (namespace) {
            "system" -> Settings.System.putString(resolver, key, value)
            "secure" -> Settings.Secure.putString(resolver, key, value)
            "global" -> Settings.Global.putString(resolver, key, value)
            else -> error("Неизвестное пространство настроек: $namespace")
        }
    }

    private suspend fun <T> access(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

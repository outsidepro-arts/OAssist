package ru.outsideproarts.oassist.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.outsideproarts.oassist.core.model.AppLanguage
import ru.outsideproarts.oassist.core.model.ChatSortOrder

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Глобальные настройки приложения. Настройки режимов (CHAT/ASSISTANT/SHARE)
 * живут отдельно и добавляются в M1.
 */
class SettingsDataStore(private val context: Context) {

    val language: Flow<AppLanguage> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE]?.let { raw -> runCatching { AppLanguage.valueOf(raw) }.getOrNull() }
            ?: AppLanguage.DEFAULT
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language.name
        }
    }

    /** Порядок сортировки списка чатов (шторка/шаринг). */
    val chatSort: Flow<ChatSortOrder> = context.settingsDataStore.data.map { prefs ->
        ChatSortOrder.fromName(prefs[KEY_CHAT_SORT])
    }

    suspend fun setChatSort(order: ChatSortOrder) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CHAT_SORT] = order.name
        }
    }

    companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("app.language")
        private val KEY_CHAT_SORT = stringPreferencesKey("chats.sort")
    }
}

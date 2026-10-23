package ru.outsideproarts.oassist.core.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.provider.Settings
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Обёртка над Android-интентами для инструментов агента.
 * [torchTracker] — процесс-скоп трекер фонарика (синглтон графа, не сообщения).
 */
class DeviceActions(
    private val context: Context,
    private val torchTracker: TorchTracker? = null,
    private val foreground: ForegroundRunner = NoopForegroundRunner(),
) {

    fun launchApp(packageName: String): ToolResult {
        bgStartBlocked()?.let { return it }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult(false, "Приложение '$packageName' не найдено")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult(true, "Приложение '$packageName' запущено")
    }

    /**
     * Activity пакета по имени: только включённые; экспорт фильтруется форматтером.
     * Пакет должен быть видим системе (launcher-приложения видимы через `<queries>`
     * MAIN/LAUNCHER); невидимый — честная ошибка, а не пустота.
     * Intent-filter'ы платформа не отдаёт (GET_INTENT_FILTERS deprecated),
     * поэтому возвращаем activity как запускаемые цели для `app_start_activity`.
     */
    suspend fun listAppActivities(
        packageName: String,
        query: String?,
        includeNonExported: Boolean,
        limit: Int,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) {
            return@withContext ToolResult(false, "Не указан package_name")
        }
        try {
            val pm = context.packageManager
            val info = getPackageInfoWithActivities(pm, packageName.trim())
            val activities = info.activities?.toList().orEmpty()
            if (activities.isEmpty()) {
                return@withContext ToolResult(true, "У приложения '$packageName' нет activity")
            }
            val entries = activities.mapNotNull { activity ->
                if (!activity.enabled) return@mapNotNull null
                val label = runCatching { activity.loadLabel(pm)?.toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: activity.name
                ActivityEntry(
                    className = activity.name,
                    label = label,
                    exported = activity.exported,
                )
            }
            ToolResult(
                true,
                formatActivityList(entries, query, includeNonExported, limit),
            )
        } catch (e: NameNotFoundException) {
            ToolResult(false, "Приложение '$packageName' не найдено или не видимо системе")
        } catch (e: Exception) {
            ToolResult(false, "Не удалось получить activity: ${e.message}")
        }
    }

    /**
     * Явный запуск activity через `ComponentName` с данными:
     * action/data(uri)/mime/categories/extras. Из не-Activity контекста —
     * всегда `FLAG_ACTIVITY_NEW_TASK`. Неэкспортированная цель заранее
     * отклоняется с явной причиной (система кинула бы `SecurityException`).
     */
    fun startAppActivity(
        packageName: String,
        activity: String,
        action: String?,
        data: String?,
        mimeType: String?,
        categories: List<String>,
        extrasJson: String?,
    ): ToolResult {
        if (packageName.isBlank()) return ToolResult(false, "Не указан package_name")
        if (activity.isBlank()) return ToolResult(false, "Не указан activity")
        val pkg = packageName.trim()
        val className = resolveActivityClassName(pkg, activity)
        val component = ComponentName(pkg, className)
        val extras = if (extrasJson.isNullOrBlank()) {
            emptyMap()
        } else {
            try {
                parseExtrasJson(extrasJson)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный extras_json")
            }
        }
        val pm = context.packageManager
        try {
            val activityInfo = getActivityInfo(pm, component)
            if (!activityInfo.exported) {
                return ToolResult(
                    false,
                    "Activity '$className' не экспортирована — запуск из другого приложения запрещён системой",
                )
            }
        } catch (e: NameNotFoundException) {
            return ToolResult(false, "Activity '$className' не найдена в пакете '$pkg'")
        } catch (e: Exception) {
            return ToolResult(false, "Не удалось проверить activity: ${e.message}")
        }
        bgStartBlocked()?.let { return it }
        return runCatching {
            val intent = Intent().apply {
                setComponent(component)
                action?.trim()?.takeIf { it.isNotEmpty() }?.let { setAction(it) }
                categories.map { it.trim() }.filter { it.isNotEmpty() }.forEach { addCategory(it) }
                val dataUri = data?.trim()?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                val type = mimeType?.trim()?.takeIf { it.isNotEmpty() }
                when {
                    dataUri != null && type != null -> setDataAndType(dataUri, type)
                    dataUri != null -> setData(dataUri)
                    type != null -> setType(type)
                }
                extras.forEach { (key, value) ->
                    when (value) {
                        is ExtraValue.Str -> putExtra(key, value.value)
                        is ExtraValue.Bool -> putExtra(key, value.value)
                        is ExtraValue.Long -> putExtra(key, value.value)
                        is ExtraValue.Double -> putExtra(key, value.value)
                    }
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "Activity '$className' запущена")
        }.getOrElse { e ->
            when (e) {
                is android.content.ActivityNotFoundException ->
                    ToolResult(false, "Activity '$className' не найдена системой")
                is SecurityException ->
                    ToolResult(false, "Запуск '$className' запрещён системой: ${e.message}")
                else -> ToolResult(false, "Не удалось запустить activity: ${e.message}")
            }
        }
    }

    /**
     * Приложения с launcher-ярлыками (паттерн лаунчеров через `<queries>` MAIN/LAUNCHER,
     * QUERY_ALL_PACKAGES не требуется). Иконки не грузим — модель работает с текстом.
     */
    suspend fun listApps(
        query: String?,
        includeSystem: Boolean,
        limit: Int,
    ): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val resolved = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
            val byPackage = LinkedHashMap<String, AppEntry>()
            for (info in resolved) {
                val activity = info.activityInfo ?: continue
                val appInfo = activity.applicationInfo ?: continue
                if (!activity.enabled || !appInfo.enabled) continue
                val packageName = activity.packageName
                val existing = byPackage[packageName]
                if (existing != null) {
                    byPackage[packageName] = existing.copy(entries = existing.entries + 1)
                    continue
                }
                val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: packageName
                byPackage[packageName] = AppEntry(
                    packageName = packageName,
                    label = label,
                    isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            ToolResult(
                true,
                formatAppList(byPackage.values.toList(), query, includeSystem, limit),
            )
        }.getOrElse { ToolResult(false, "Не удалось получить список приложений: ${it.message}") }
    }

    fun openWeb(url: String): ToolResult {
        bgStartBlocked()?.let { return it }
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "Открыт URL: $normalized")
        }.getOrElse { ToolResult(false, "Не удалось открыть URL: ${it.message}") }
    }


    /**
     * Есть ли у системы activity, которая отвечает на intent (как `startActivity` с DEFAULT-категорией).
     * Без обработчика `startActivity` бросает исключение, а у OEM-часов это может молча не сработать —
     * поэтому проверяем заранее и возвращаем честную ошибку.
     */
    private fun hasHandler(intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

    /**
     * Pre-flight против тихой блокировки HyperOS/MIUI (опа 10021): `startActivity` из фона
     * молча отбрасывается (START_ABORTED без исключения) — вместо ложного успеха честная ошибка.
     */
    private fun bgStartBlocked(): ToolResult? =
        if (BgStartGuard.isBlocked(context, AppVisibility.isForeground)) {
            ToolResult(false, BgStartGuard.blockedMessage(context.packageName))
        } else {
            null
        }

    /** Обработчики действия (SET_ALARM/SET_TIMER) — для текста ошибки модели. */
    private fun listClockHandlers(action: String): List<ClockHandler> =
        context.packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                ClockHandler(pkg, info.loadLabel(context.packageManager).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }

    /**
     * [packageName] не-blank → адресный запрос: пакет обязан уметь действие,
     * иначе ошибка со списком обработчиков (без молчаливой подмены).
     * Иначе — дефолтный резолв; нет обработчика — тоже ошибка со списком.
     */
    private fun clockHandlerError(action: String, packageName: String?, intent: Intent): ToolResult {
        val handlers = listClockHandlers(intent.action ?: return ToolResult(false, "Не задано действие"))
        val requested = packageName?.takeIf { it.isNotBlank() }
        return ToolResult(false, noClockHandlerError(action, requested, handlers))
    }

    fun setAlarm(hour: Int, minute: Int, label: String?, packageName: String? = null): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!packageName.isNullOrBlank()) intent.setPackage(packageName)
        if (!hasHandler(intent)) return clockHandlerError("ставить будильники", packageName, intent)
        bgStartBlocked()?.let { return it }
        return runCatching {
            context.startActivity(intent)
            val labelText = label?.let { " с меткой '$it'" } ?: ""
            ToolResult(true, "Будильник установлен на %02d:%02d%s".format(hour, minute, labelText))
        }.getOrElse { ToolResult(false, "Не удалось установить будильник: ${it.message}") }
    }

    fun openSettings(action: String?): ToolResult {
        bgStartBlocked()?.let { return it }
        val intent = when (action) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "Настройки открыты")
        }.getOrElse { ToolResult(false, "Не удалось открыть настройки: ${it.message}") }
    }

    // --- M2.3: коммуникации и данные ---

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun noPermissionResult(permission: String): ToolResult =
        ToolResult(
            false,
            "Нет разрешения $permission — выдайте его в настройках приложения OAssist, затем повторите",
        )

    fun placeCall(number: String): ToolResult {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return noPermissionResult(Manifest.permission.CALL_PHONE)
        }
        bgStartBlocked()?.let { return it }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "Звонок на номер $number")
        }.getOrElse { ToolResult(false, "Не удалось позвонить: ${it.message}") }
    }

    fun sendSms(to: String, body: String): ToolResult {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return noPermissionResult(Manifest.permission.SEND_SMS)
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.size <= 1) {
                smsManager.sendTextMessage(to, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            }
            ToolResult(true, "SMS на номер $to отправлено")
        }.getOrElse { ToolResult(false, "Не удалось отправить SMS: ${it.message}") }
    }

    fun findContacts(query: String): ToolResult {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return noPermissionResult(Manifest.permission.READ_CONTACTS)
        }
        return runCatching {
            val found = mutableListOf<String>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && found.size < MAX_CONTACT_RESULTS) {
                    found.add("${cursor.getString(nameIdx)} — ${cursor.getString(numberIdx)}")
                }
            }
            if (found.isEmpty()) {
                ToolResult(true, "Контакты по запросу «$query» не найдены")
            } else {
                ToolResult(true, "Найдено: ${found.joinToString("; ")}")
            }
        }.getOrElse { ToolResult(false, "Не удалось найти контакты: ${it.message}") }
    }

    /**
     * Журнал вызовов: только чтение, выборка новыми вперёд с потолком [CALL_LOG_FETCH_CAP].
     * Фильтры и лимит применяет форматтер; `LIMIT` в selection провайдер запрещает —
     * только `LIMIT_PARAM_KEY` в URI.
     */
    suspend fun readCallLog(
        query: String?,
        type: CallType?,
        limit: Int,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            return@withContext noPermissionResult(Manifest.permission.READ_CALL_LOG)
        }
        runCatching {
            val entries = mutableListOf<CallLogEntry>()
            val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, CALL_LOG_FETCH_CAP.toString())
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.NUMBER_PRESENTATION,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val presentationIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER_PRESENTATION)
                while (cursor.moveToNext() && entries.size < CALL_LOG_FETCH_CAP) {
                    entries.add(
                        CallLogEntry(
                            number = cursor.getString(numberIdx),
                            name = cursor.getString(nameIdx),
                            dateMillis = cursor.getLong(dateIdx),
                            durationSec = cursor.getInt(durationIdx),
                            type = callTypeFromInt(cursor.getInt(typeIdx)),
                            presentation = callPresentationFromInt(cursor.getInt(presentationIdx)),
                        ),
                    )
                }
            }
            ToolResult(true, formatCallLog(entries, query, type, limit))
        }.getOrElse { ToolResult(false, "Не удалось прочитать журнал вызовов: ${it.message}") }
    }

    fun addCalendarEvent(title: String, startMillis: Long, endMillis: Long, description: String?): ToolResult {
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return noPermissionResult(Manifest.permission.WRITE_CALENDAR)
        }
        return runCatching {
            val calendarId = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null,
                null,
            )?.use { cursor ->
                var first: Long? = null
                var primary: Long? = null
                val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryIdx = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    if (first == null) first = id
                    if (cursor.getInt(primaryIdx) == 1) primary = id
                }
                primary ?: first
            } ?: throw IllegalStateException("Нет доступного календаря")
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw IllegalStateException("Провайдер календаря отклонил вставку")
            ToolResult(true, "Событие «$title» добавлено в календарь")
        }.getOrElse { ToolResult(false, "Не удалось добавить событие: ${it.message}") }
    }

    fun setTimer(seconds: Int, label: String?, packageName: String? = null): ToolResult {
        if (seconds <= 0) return ToolResult(false, "Длительность таймера должна быть положительной")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!packageName.isNullOrBlank()) intent.setPackage(packageName)
        if (!hasHandler(intent)) return clockHandlerError("запускать таймеры", packageName, intent)
        bgStartBlocked()?.let { return it }
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "Таймер на $seconds с. запущен")
        }.getOrElse { ToolResult(false, "Не удалось запустить таймер: ${it.message}") }
    }

    fun deviceInfo(): ToolResult {
        val info = "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}; " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
            "локаль ${Locale.getDefault().toLanguageTag()}; " +
            "часовой пояс ${TimeZone.getDefault().id}"
        return ToolResult(true, info)
    }

    fun copyToClipboard(text: String): ToolResult {
        return runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: return ToolResult(false, "Буфер обмена недоступен")
            clipboard.setPrimaryClip(ClipData.newPlainText("OAssist", text))
            ToolResult(true, "Текст скопирован в буфер обмена")
        }.getOrElse { ToolResult(false, "Не удалось скопировать: ${it.message}") }
    }

    // --- M2.7: интент-хвосты ---

    fun openNavigation(query: String?, latitude: Double?, longitude: Double?): ToolResult {
        bgStartBlocked()?.let { return it }
        val uri = when {
            latitude != null && longitude != null -> Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
            !query.isNullOrBlank() -> {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                Uri.parse("geo:0,0?q=$encoded")
            }
            else -> return ToolResult(false, "Укажите query или координаты lat/lng")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            val target = query ?: "$latitude,$longitude"
            val encoded = java.net.URLEncoder.encode(target, "UTF-8")
            return openWeb("https://www.google.com/maps/search/?api=1&query=$encoded")
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "Карты открыты")
        }.getOrElse { ToolResult(false, "Не удалось открыть карты: ${it.message}") }
    }

    fun shareText(text: String): ToolResult {
        bgStartBlocked()?.let { return it }
        if (text.isBlank()) return ToolResult(false, "Не указан text")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            ToolResult(true, "Выбор приложения для отправки открыт")
        }.getOrElse { ToolResult(false, "Не удалось поделиться: ${it.message}") }
    }

    fun setFlashlight(on: Boolean): ToolResult {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return noPermissionResult(Manifest.permission.CAMERA)
        }
        return runCatching {
            val cameraManager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                ?: return ToolResult(false, "Камера недоступна")
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult(false, "На устройстве нет вспышки")
            cameraManager.setTorchMode(cameraId, on)
            torchTracker?.onTorchChanged(cameraId, on)
            ToolResult(true, if (on) "Фонарик включён" else "Фонарик выключен")
        }.getOrElse { ToolResult(false, "Не удалось переключить фонарик: ${it.message}") }
    }

    fun pressMediaButton(button: String): ToolResult {
        val keyCode = when (button.lowercase()) {
            "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ToolResult(false, "Кнопка: play_pause/next/prev")
        }
        return runCatching {
            val audio = context.getSystemService(android.media.AudioManager::class.java)
                ?: return ToolResult(false, "Аудиосервис недоступен")
            val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)
            ToolResult(true, "Медиа-кнопка нажата: $button")
        }.getOrElse { ToolResult(false, "Не удалось нажать медиа-кнопку: ${it.message}") }
    }

    // Разрешение проверяется в рантайме выше + try/catch вокруг вызова: lint этого не видит.
    // Кэшу (getLastKnownLocation) здесь нет сознательно: он отдавал чужие точки
    // (прецедент — Кремль человеку, который там не был). Только свежий фикс.
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun freshLocation(): ToolResult {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return noPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Гео-фикс до 20 с — долгие операции идут под foreground-сервисом (M5.3),
        // чтобы систему не убила процесс посреди ожидания.
        return foreground.run { freshLocationInner() }
    }

    private suspend fun freshLocationInner(): ToolResult {
        return runCatching {
            val locationManager = context.getSystemService(android.location.LocationManager::class.java)
                ?: return ToolResult(false, "Сервис геопозиции недоступен")
            val enabled = locationManager.getProviders(true)
            val wantGps = enabled.contains(android.location.LocationManager.GPS_PROVIDER)
            val wantNet = enabled.contains(android.location.LocationManager.NETWORK_PROVIDER)
            if (!wantGps && !wantNet) {
                return ToolResult(false, "Геопозиция выключена — включите её в настройках")
            }
            val fix = awaitFreshFix(locationManager, wantGps, wantNet)
                ?: return ToolResult(false, NO_FIX_MESSAGE)
            @Suppress("DEPRECATION")
            val mock = fix.isMock
            ToolResult(
                true,
                formatFix(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyM = fix.accuracy.toInt(),
                    provider = fix.provider ?: "unknown",
                    mock = mock,
                ),
            )
        }.getOrElse { ToolResult(false, "Не удалось получить координаты: ${it.message}") }
    }

    /**
     * Ждать живого фикса: GPS и сеть параллельно, победа точного (≤50 м).
     * К таймауту — лучший свежий из пришедших, если он не грубее 500 м
     * (VPN/эмуляторные точки в километрах отбрасываются, а не презентуются).
     * Мок-флаги не фильтруем, но помечаем в ответе — пусть модель решает.
     * Разрешение проверено вызывающим + try/catch: lint этого не видит.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun awaitFreshFix(
        locationManager: android.location.LocationManager,
        wantGps: Boolean,
        wantNet: Boolean,
    ): android.location.Location? {
        val done = CompletableDeferred<Unit>()
        var finalResult: android.location.Location? = null
        val listener = android.location.LocationListener { loc ->
            if (done.isCompleted) return@LocationListener
            val current = finalResult
            if (loc.accuracy <= GOOD_ACCURACY_M) {
                finalResult = loc
                done.complete(Unit)
            } else if (current == null || loc.accuracy < current.accuracy) {
                finalResult = loc
            }
        }
        // Колбэки — на Main-лупере явно (без Dispatchers.Main, как в UiActions).
        val looper = android.os.Looper.getMainLooper()
        try {
            if (wantGps) {
                locationManager.requestLocationUpdates(
                    android.location.LocationManager.GPS_PROVIDER, 0L, 0f, listener, looper,
                )
            }
            if (wantNet) {
                locationManager.requestLocationUpdates(
                    android.location.LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, looper,
                )
            }
            kotlinx.coroutines.withTimeoutOrNull(FIX_TIMEOUT_MS) { done.await() }
        } catch (e: SecurityException) {
            return null
        } finally {
            runCatching { locationManager.removeUpdates(listener) }
        }
        return finalResult?.takeIf { it.accuracy <= MAX_ACCEPTABLE_ACCURACY_M }
    }

    /** PackageInfo с activities: на API 33+ — через `PackageInfoFlags`, ниже — deprecated int. */
    private fun getPackageInfoWithActivities(
        pm: PackageManager,
        packageName: String,
    ): android.content.pm.PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        }
    }

    /** ActivityInfo цели: на API 33+ — через `ComponentInfoFlags`, ниже — deprecated int. */
    private fun getActivityInfo(
        pm: PackageManager,
        component: ComponentName,
    ): android.content.pm.ActivityInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }
    }

    companion object {
        private const val MAX_CONTACT_RESULTS = 5
        private const val FIX_TIMEOUT_MS = 20_000L
        private const val GOOD_ACCURACY_M = 50f
        private const val MAX_ACCEPTABLE_ACCURACY_M = 500f
        private const val NO_FIX_MESSAGE =
            "Не удалось определить местоположение за 20 с: выйдите на открытое место, " +
                "включите GPS и Wi-Fi в настройках"
        const val DND_ACCESS_MESSAGE =
            "Нет доступа «Не беспокоить»: выдайте его в системных настройках " +
                "(Звук → Не беспокоить → Доступ), затем повторите"
        const val WRITE_SETTINGS_MESSAGE =
            "Нет права изменять системные настройки: выдайте его в системных настройках " +
                "при первом запросе, затем повторите"
    }

    // --- M5.2: громкость, «Не беспокоить», яркость (спецдоступы, не runtime) ---

    /** Громкость потока процентами 0–100. Потоки: music/ring/alarm/notification. */
    fun setVolume(stream: String, percent: Int): ToolResult {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
            ?: return ToolResult(false, "Аудиосервис недоступен")
        val type = when (stream.lowercase()) {
            "music", "музыка" -> android.media.AudioManager.STREAM_MUSIC
            "ring", "звонок" -> android.media.AudioManager.STREAM_RING
            "alarm", "будильник" -> android.media.AudioManager.STREAM_ALARM
            "notification", "уведомления" -> android.media.AudioManager.STREAM_NOTIFICATION
            else -> return ToolResult(false, "Поток: music/ring/alarm/notification")
        }
        if (percent !in 0..100) return ToolResult(false, "Громкость: 0–100")
        return runCatching {
            val max = audio.getStreamMaxVolume(type)
            audio.setStreamVolume(type, percentToIndex(percent, max), 0)
            ToolResult(true, "Громкость $stream: $percent%")
        }.getOrElse { ToolResult(false, "Не удалось задать громкость: ${it.message}") }
    }

    /** Режим «Не беспокоить»: вкл/выкл. Требует спецдоступ (не runtime-разрешение). */
    fun setDnd(enabled: Boolean): ToolResult {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return ToolResult(false, "Сервис уведомлений недоступен")
        if (!manager.isNotificationPolicyAccessGranted) {
            return ToolResult(false, DND_ACCESS_MESSAGE)
        }
        return runCatching {
            manager.setInterruptionFilter(
                if (enabled) {
                    android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                } else {
                    android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                },
            )
            ToolResult(true, if (enabled) "«Не беспокоить» включён" else "«Не беспокоить» выключен")
        }.getOrElse { ToolResult(false, "Не удалось переключить режим: ${it.message}") }
    }

    /** Яркость экрана процентами 0–100 (переводит в ручной режим). Требует спецдоступ. */
    fun setBrightness(percent: Int): ToolResult {
        if (percent !in 0..100) return ToolResult(false, "Яркость: 0–100")
        if (!Settings.System.canWrite(context)) {
            return ToolResult(false, WRITE_SETTINGS_MESSAGE)
        }
        return runCatching {
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, percentToBrightness(percent))
            ToolResult(true, "Яркость: $percent%")
        }.getOrElse { ToolResult(false, "Не удалось задать яркость: ${it.message}") }
    }

    /** Живой снимок состояния: всё read-only, без runtime-разрешений. */
    fun deviceState(): ToolResult {        return runCatching {
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val music = audio?.isMusicActive == true
            val ringer = when (audio?.ringerMode) {
                android.media.AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
                android.media.AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
                else -> RingerMode.NORMAL
            }
            val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
                ?.isWifiEnabled == true
            val batteryIntent = context.registerReceiver(
                null,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level = batteryIntent?.getIntExtra(
                android.os.BatteryManager.EXTRA_LEVEL, -1,
            ) ?: -1
            val scale = batteryIntent?.getIntExtra(
                android.os.BatteryManager.EXTRA_SCALE, -1,
            ) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
            val status = batteryIntent?.getIntExtra(
                android.os.BatteryManager.EXTRA_STATUS, -1,
            ) ?: -1
            val charging = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING,
                android.os.BatteryManager.BATTERY_STATUS_FULL -> true
                -1 -> null
                else -> false
            }
            val screen = context.getSystemService(android.os.PowerManager::class.java)
                ?.isInteractive == true
            ToolResult(
                true,
                formatDeviceState(
                    LiveDeviceState(
                        torchOn = torchTracker?.snapshot(),
                        musicActive = music,
                        wifiOn = wifi,
                        batteryPct = batteryPct,
                        charging = charging,
                        screenOn = screen,
                        ringer = ringer,
                    ),
                ),
            )
        }.getOrElse { ToolResult(false, "Не удалось прочитать состояние: ${it.message}") }
    }
}

/** Проценты 0–100 → индекс потока громкости. Чистая — покрыта тестом. */
fun percentToIndex(percent: Int, max: Int): Int =
    (percent.coerceIn(0, 100) * max / 100).coerceIn(0, max)

/** Проценты 0–100 → яркость 1–255 (0 гасит экран — запрещён). Чистая — покрыта тестом. */
fun percentToBrightness(percent: Int): Int =
    (percent.coerceIn(0, 100) * 255 / 100).coerceIn(1, 255)

/** Чистое форматирование свежего фикса (провайдер и mock видны модели явно). */
fun formatFix(
    latitude: Double,
    longitude: Double,
    accuracyM: Int,
    provider: String,
    mock: Boolean,
): String {
    return "Координаты: $latitude,$longitude " +
        "(точность $accuracyM м, свежий фикс, $provider" +
        (if (mock) ", mock" else "") + ")"
}

# MEMORY.md — память проекта OAssist

Файл памяти для агентов OpenCode. Обновляется по ходу разработки. Последнее состояние — в конце.

## Зафиксированные решения

### Идентичность
- Имя: **OAssist**; пакет `ru.outsideproarts.oassist`; чистый Kotlin; MIT; opensource без Google Play, в первую очередь для личного использования.
- **Русский — приоритетный язык** (UI-ресурсы `values/` = ru, `values-en/` = en; промпты `assets/prompts/ru` + `en`; STT/TTS `ru-RU`).

### Платформа и стек
- **minSdk 31** (Android 12), compileSdk 36.
- Jetpack Compose + Material 3, Coroutines/Flow, Ktor 3 (SSE/WS), kotlinx.serialization, Room (KSP), DataStore.
- **Ручной DI (`AppGraph`)** — без Hilt/Koin.
- Gradle 9.4.1 (`c:\dev\gradle\gradle-9.4.1`), Android SDK `c:\dev\android-sdk`, JDK `c:\dev\jdk`.
- Мультипроект: `app` + `core/{model,a11y,data,net,provider,agent,tools,device,mcp,speech}` + `feature/{chat,assistant,share,settings}`.

### Область (v1)
- **Включено:** системный ассистент (assist-жест), интенты (звонки/SMS/календарь/будильники/приложения/медиа/настройки), AccessibilityService (пассивный, по запросу), Shizuku/root, максимум провайдеров LLM + **OpenCode**, MCP **клиент + сервер**, кастомизация (промпты на режимы, скиллы, память), мультиязычность, STT (системный + кастомный HTTP + аудио-in), TTS (системный + онлайн + **экран чтец**), нарратив работы.
- **Вне v1 (v2):** Xposed-перехват кнопки питания / Gemini / XiaoAI / Breeno. В v1 — только стандартный assist-жест.

### Точка входа ассистента
- `VoiceInteractionService` + `VoiceInteractionSession(Service)` + `@xml/voice_interaction_service` (`supportsAssist`, `supportsScreenshot`, `supportsLaunchVoiceAssistFromKeyguard`).
- **`RecognitionService` + `@xml/recognition_service` — обязательный стаб** для `ROLE_ASSISTANT` (подтверждено AOSP `VoiceInteractionManagerService` + паттерн Eta).
- `AssistantOverlayService` — UI (fullscreen `TYPE_APPLICATION_OVERLAY`, Compose; паттерн `setViewTreeLifecycleOwner`/`SavedStateRegistryOwner`).
- `AssistActivity` (`ACTION_ASSIST`/`VOICE_COMMAND`) — фолбэк.
- Session принимает `AssistStructure` + скриншот (`SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT`), форвардит в `AgentRuntime`, **UI не держит**. Контекст экрана — одноразовый, ограниченный, не персистится.
- Онбординг: `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` + чеклист разрешений.

### Провайдеры и модели
- `ProviderProfile` — **несколько** профилей: `OPENAI_COMPAT | ANTHROPIC | GEMINI | OPENCODE`; `baseUrl`, `apiKeyRef`, `extraHeaders`, `enabled`.
- `ModeConfig` — три режима `CHAT | ASSISTANT | SHARE`, у каждого **свои** провайдер + модель + initial prompt (+ extras).
- **Общее на все режимы:** скиллы, память, MCP, STT/TTS, привилегии, каталог тулов.
- Список моделей — **только** с провайдера:
  - `OPENAI_COMPAT` → `GET {base}/models` (Bearer)
  - `ANTHROPIC` → `GET {base}/v1/models` (`x-api-key`)
  - `GEMINI` → `GET {base}/v1beta/models` (`x-goog-api-key`)
  - `OPENCODE` → `GET {base}/provider` → `{ all, default, connected }` (дерево провайдер→модели)
- Кэш + «Обновить список», фолбэк — ручной ввод `modelId`, capability (`vision`/`audioIn`/`tools`/`reasoning`) — из ответа или пробой, правится вручную.

### Режимы
- `CHAT` — диалог в приложении. `ASSISTANT` — assist-панель (extras: авто-озвучка, нарратив, авто-закрытие, прикреплять ли скриншот). `SHARE` — вход из Share Activity (extras: что делать с `{shared_content}`, показывать ли в чате).
- Промпты на режим с плейсхолдерами `{locale} {time} {device} {memory} {skills} {screen_context}` (+ `{shared_content}` в SHARE). Дефолты — ru, фолбэк en.

### Доступность (TalkBack) — сквозное требование
- Модуль `core/a11y` — инварианты в одном месте.
- Обязательно: имена/роли/`stateDescription`, `heading()`, `paneTitle`, touch ≥ 48dp, контраст AA, порядок фокуса, `LiveRegion.Polite` для динамики, `Assertive` для ошибок, `announceForAccessibility` для транзиентов.
- **Жест ≠ единственный путь**: у каждого swipe/long-press — кнопка/меню **и** `CustomAccessibilityAction`.
- Definition of Done каждого PR включает TalkBack-проход.

### Речь (согласовано 2026-09-24)
- **Ответ агента** — `SpeechOutputChannel`: `OFF | SYSTEM_TTS | ONLINE_TTS | SCREEN_READER`.
  - `SCREEN_READER` — без звука: каждое завершённое предложение → скрытый announcer-узел (`LiveRegion.Polite`). Видимый текст — **не** live region (иначе TalkBack перечитывает всё). **Гранулярность — по предложениям, как TTS** (согласовано).
  - Плюс действие `Озвучить ответ` в Accessibility actions.
- **Нарратив работы** (размышления, инструменты) — `Narrator`, **только** через экранный чтец, отдельным тумблером (не занимает звук TTS) (согласовано). Одновременно визуально — сворачиваемый timeline, навигируемый TalkBack.
- Механизм канала SCREEN_READER — **через a11y-объявления** (согласовано).

### Секреты (сквозное требование)
- **Все** ключи/токены/пароли через `core/data/secret/SecretStore` (Android Keystore AES-256-GCM, non-extractable, StrongBox с фолбэком).
- Область: API-ключи провайдеров, значения `extraHeaders`, Basic Auth OpenCode, Bearer MCP, ключи онлайн-TTS, auth кастомного STT, пароль экспорта настроек.
- **Без `setUserAuthenticationRequired`** + `setUnlockedDeviceRequired(false)` — ассистент должен работать с экрана блокировки. Опциональный тумблер «требовать разблокировку» — с предупреждением.
- UI: маска `•••• (сохранён)` + «Заменить»/«Удалить», значение не переотображается.
- `android:allowBackup="false"`, `dataExtractionRules` исключают `secrets.pb`. Direct Boot: секреты + VIS в device-protected storage.
- Redacting-логгер вырезает секреты по паттернам и alias.

### Инструменты
- Слои: `intent` → `ui` (a11y) → `privileged` (Shizuku/root). Минимальный `PrivilegeLevel` на инструмент, явная деградация.
- `ActionRisk` + `AutoApprovalPolicy` (авто / спросить / всегда; голосовое подтверждение опц.).
- Конкретные тулы: `intent.*` (call, sms, calendar, alarm/timer/stopwatch, app, settings, media, web, nav, share, contact, clipboard, flashlight/volume/dnd/brightness, device_info, location), `notify.*`, `ui.*` (get_screen, screenshot, click/long_click, set_text, scroll, swipe, global_*, wait_for, find_nodes), `memory.*`, `skill.*`, `shell.*`, `mcp.*`.
- **Со-использование с TalkBack:** наш сервис без accessibility-feedback, `dispatchGesture`/`performAction` работают при активном touch exploration.

### MCP
- **Клиент:** Streamable HTTP (+ Bearer, кастомные заголовки), `tools/list`, `tools/call`, авто-фолбэк версий, неймспейс `сервер__инструмент`, токены в Keystore.
- **Сервер:** Ktor на `127.0.0.1:<порт>` (опц. Basic Auth), поверхность над `intent.*`/`ui.*`/`notify.*`. **Это канал, которым OpenCode управляет телефоном.**

### OpenCode-интеграция
- `RemoteAgentBackend` / `OpenCodeClient` — официальный HTTP+SSE API.
- Эндпоинты: `/global/health`, `/provider`, `/config/providers`, `/session*`, `/session/:id/message`, `/prompt_async`, `/command`, `/event` (SSE), `/abort`, `/todo`, `/agent`, `/mcp`.
- Auth: HTTP Basic (`OPENCODE_SERVER_USERNAME`/`OPENCODE_SERVER_PASSWORD`).
- Модели — через `GET /provider` (`{ all, default, connected }`).

### Привилегии
- `PrivilegeLevel: NONE < SHIZUKU < ROOT`.
- Shizuku/Sui: bind + `UserService` (Secure/Global-настройки, скрытые API, grant разрешений).
- Root: `su` с таймаутом и лимитом вывода; фолбэк жестов через `input`/`sendevent`.

### Язык
- `values/strings.xml` = **ru** (default-фолбэк), `values-en/strings.xml` = en.
- Выбор языка в приложении: `Системный / Русский / English`, **по умолчанию Русский**. API 33+ `LocaleManager`, API 31–32 — `AppCompatDelegate.setApplicationLocales`.

## Дорожная карта

| Этап | Срез | Критерий готовности |
|---|---|---|
| **M0** Каркас + a11y-фундамент | мультимодуль, version catalog, Compose-навигация, `AppGraph`, Room/DataStore, тема M3 (light+dark, AA), `core/a11y` + первые экраны под TalkBack, строки ru + en, выбор языка, `SecretStore`+`KeystoreAead`, `allowBackup=false`, redacting-логгер | `assembleDebug` стартует; TalkBack проходит по экранам без «кнопка» |
| **M1** Ассистент end-to-end | VIS-стек + RoleManager + `AssistActivity`; `OpenAiCompatibleClient`; `AgentLoop` + 3 тула (`app_launch`, `web_open`, `alarm_set`); оверлей со стримом (доступный); `SpeechOutput` OFF/SYSTEM_TTS/SCREEN_READER (по предложениям); `Narrator` + timeline; чат с историей + Accessibility actions; `ShareActivity`; режимы-промпты; **профили провайдеров + запрос списка моделей + пикер, отдельно per-режим** | «Поставь будильник на 7» через assist-жест → будильник стоит; тот же сценарий вслепую с TalkBack |
| **M2** Устройство | a11y tool-слой (снапшот, тапы, жесты, скриншот); полный `intent.*`; `NotificationListener`; `ActionRisk`/подтверждения | «Найди Сашу и напиши SMS»; диалоги подтверждения читаются TalkBack |
| **M3** Провайдеры + MCP | `ANTHROPIC`, `GEMINI`, `OPENCODE` + их endpoint'ы списка моделей; ручной ввод `modelId`; capability-проба; MCP клиент+сервер | OpenCode читает экран и тапает через наш MCP-сервер |
| **M4** Речь полно + скиллы | STT (система + custom HTTP + аудио-in); `RecognitionService`; `ONLINE_TTS`, поток по предложениям, barge-in; память; скиллы; редактор промптов | Hands-free диалог; смена канала TTS ↔ экран чтец без потери очереди предложений |
| **M5** Привилегии + полировка | Shizuku-UserService, root-exec, фолбэк жестов; полный i18n ru/en; keep-alive a11y, FGS `specialUse`, экспорт/импорт, Direct Boot, полный wipe; юнит+инструментальные тесты семантики | Полный набор ТЗ + TalkBack-чеклист по всем Milestone |

## Definition of Done (каждый PR)

1. Юнит-тесты там, где применимо; `assembleDebug` зелёный.
2. Новые строки — сразу в `values/` (ru) и `values-en/`.
3. **TalkBack-проход:** имена/роли/состояния, touch targets, порядок фокуса, у жестов — альтернативы и `CustomAccessibilityAction`.
4. **Секреты** не попадают в логи/историю/экспорт/бэкап. Новый секрет = alias в `SecretStore`. Поля ввода маскируются, значение не переотображается.
5. Нет захардкоженных текстов в объявлениях — всё через ресурсы.
6. Новая модель/провайдер — **только** через запрос списка у провайдера или ручной ввод id.

## Открытые вопросы

- JDK в `c:\dev\jdk` — проверить раскладку (ожидаем `bin\java.exe`), зафиксировать `org.gradle.java.home`/`JAVA_HOME` в M0.
- Per-mode-набор тулов (пока глобальный каталог) — возможное расширение, модель данных позволяет.
- Опциональное шифрование Room-истории — расширение (сейчас redacting + секреты отдельно).
- Custom IME для ввода текста вне `EditText` — после clipboard+paste (M2/M5).

## Текущее состояние

- **Фаза:** M1 завершён. M2 завершён и протестирован на устройстве (2026-10-04): 30 тулов, подтверждения, настройки инструментов, a11y-сервис, уведомления, web_fetch. **M3 (код) — ЗАВЕРШЁН (2026-10-04):** нативные Anthropic + Gemini, capability-проба, MCP-клиент, Basic Auth. Остаток M3 — ручной чеклист ниже (ключи + MCP-сервер на устройстве).
- **M2.1 (подтверждения, каркас безопасности) — ГОТОВ (2026-10-04):**
  - `core/model/Approval.kt`: `ApprovalDecision` (ALLOW_ONCE/ALWAYS_ALLOW/DENY), `ApprovalRequest` (callId/toolName/argumentsJson/summary/risk).
  - `core/agent/ApprovalGate.kt`: `ApprovalPolicy` + `InMemoryApprovalPolicy` (AUTO сразу, ASK/ALWAYS_ASK через диалог, `clearAlwaysAllowed`), `ApprovalController` (await/resolve + `prepare` против гонки ответ-раньше-паузы).
  - `Tool.risk/privilege/describeForConfirmation` (дефолт AUTO/NONE); неизвестный тул — fail-closed как ASK. Все 5 текущих тулов — AUTO (поведение M1 не изменилось).
  - `AgentLoop`: пауза на `ChatChunk.ApprovalNeeded`, `resolveApproval()`, `AgentSession(chunks, agent)` вместо голого Flow; политика общая на процесс (`AppGraph.approvalPolicy`).
  - UI: `ApprovalDialog` в чате (Разрешить/Всегда/Отклонить, закрытие = DENY), Share/Assist/VIS — auto-DENY (диалогов там нет); в настройках — «Сбросить “всегда разрешать”».
  - Тесты `AgentLoopApprovalTest` (5: allow/deny/always/auto/unknown) + старые (3) — зелёные; `:app:compileDebugKotlin` — зелёный. Строки ru+en (chat_approval_*, approvals_*).
  - Отложено: персист политики в DataStore; диалог в Share/Assist; ASK-тулы появятся в M2.2 (call/sms/…).
- **M2.2 (настраиваемые инструменты, экран «Инструменты агента») — ГОТОВ (2026-10-04):**
  - `core/model/ToolPreferences` (disabled + askOverrides, только дельта от дефолтов) + `core/data/ToolConfigStore` (DataStore `tool_config`, device-protected) + `DataContainer.toolConfigs`.
  - `core/agent/ToolCatalog` (единая сборка каталога — убрано дублирование списков в AppGraph/MainActivity) + `ToolRegistry(disabled, askOverrides)`: выключенные исключены из схем для модели и дают явную ошибку «отключён в настройках»; `effectiveRisk` (true→мин. ASK, false→AUTO, unknown→ASK fail-closed). `AgentLoop` использует эффективный риск; `AgentFactory.agentFor` стал suspend (читает снапшот преференсов).
  - UI: `ToolsSettingsScreen` (маршрут `tools_settings` из настроек): карточка на тул (название+описание ru/en, Switch «Включён», Switch «Спрашивать перед выполнением» только когда включён, stateDescription вкл/выкл) + секция безопасности (сброс «всегда разрешать», сброс настроек тулов к дефолтам) + кнопка «Назад». Сброс «всегда» переехал из главных настроек в этот экран.
  - Тесты `ToolRegistryConfigTest` (4) — зелёные; всего 12/12; `:app:compileDebugKotlin` — зелёный.
  - Инфра-инцидент: рецидив лока `core/model …/classes.jar` другим процессом + зомби KotlinCompileDaemon (10:04, свой) — вылечено `gradlew --stop` (зомби ушёл с демоном). (Устарело: актуальные запреты — в AGENTS.md «Дисциплина длинных команд»: никаких `--stop`/`clean*`/`--offline`/`--no-build-cache` в агентских прогонах.)
- **M2.3 (полный intent.*, 2026-10-04):**
  - 7 новых тулов: `call_place` (ASK), `sms_send` (ASK, multipart), `contact_find` (AUTO), `calendar_add` (ASK, epoch-millis, primary-календарь), `timer_set` (AUTO), `device_info` (AUTO), `clipboard_copy` (AUTO). `describeForConfirmation` у ASK-тулов; без разрешения — явная ошибка «выдайте в разделе Инструменты агента», не молча.
  - `Tool.requiredPermissions` (дефолт пусто); манифест: `CALL_PHONE`, `SEND_SMS`, `READ_CONTACTS`, `READ/WRITE_CALENDAR`. Выдача — кнопками в карточках тулов (`RequestMultiplePermissions`, состояние обновляется после выдачи). Урок: `context.checkSelfPermission` вместо `ContextCompat` — лишний `core-ktx` тянул некэшированные транзитивные jar и валил офлайн-сборку.
  - Все 12 тулов — в `ToolCatalog`, дескрипторах и экране настроек (названия+описания ru/en).
  - Постфикс `web_search` (2026-10-04): раньше — голый `ACTION_WEB_SEARCH` с литералом `"query"`: без обработчика в системе валил `ActivityNotFoundException` → модель уходила в цикл повторов. Теперь: константа `SearchManager.QUERY`, проверка `resolveActivity`, фолбэк на поиск через браузер (`openWeb`), пустой запрос — явная ошибка с подсказкой про параметр `query`.
- **M2.4 (AccessibilityService + ui.*, 2026-10-04):**
  - `OAssistAccessibilityService` (пассивный: события игнорируются, без feedback; `canPerformGestures=true`, `typeWindowsChanged`, `feedbackGeneric`) + `@xml/accessibility_service` + манифест (`BIND_ACCESSIBILITY_SERVICE`). `AppGraph.uiActions` (`UiActions { instance }`).
  - `UiSnapshot.kt` (чистая модель `UiNode`/`UiRect` + `formatSnapshot` «`[i] Class «label» (flags) [l,t-r,b]`») + `UiActions` (BFS-снапшот ≤100 узлов, click/long_click/set_text/scroll/swipe через `dispatchGesture` с `Handler(MainLooper)`/таймаутом, global back/home/recents, `wait_for` опросом ≤10 с). Без сервиса — явная ошибка «включите в системных настройках».
  - 8 тулов: `ui_get_screen` (AUTO), `ui_click`/`ui_long_click`/`ui_set_text`/`ui_scroll`/`ui_swipe` (ASK), `ui_global`/`ui_wait_for` (AUTO). Вкладка «Ассистент»: секция «Доступ» (статус сервиса + переход в настройки, обновление по ON_RESUME). Тест `UiSnapshotFormatTest` (4) — зелёный.
  - Отложено: `ui_screenshot` (у VIS-конвейера уже есть скриншот), Custom IME (как и было — M5).
- **M2.5 (NotificationListener + notify.*, 2026-10-04):**
  - `OAssistNotificationListener` (пассивный) + манифест (`BIND_NOTIFICATION_LISTENER_SERVICE`); `AppGraph.notifyActions`; `NotifyActions` (list/open через `contentIntent.send()`/clear по ключу, cap 20) + чистый `formatNotifications` + тест `NotifyFormatTest` (2).
  - 3 тула: `notify_list` (AUTO), `notify_open`/`notify_clear` (ASK). Строка доступа — в секции «Доступ» вкладки «Ассистент» (`ACTION_NOTIFICATION_LISTENER_SETTINGS`). Отложено: `notify_reply` через RemoteInput (M5).
- **M2 (код) — ЗАВЕРШЁН (2026-10-04):** 23 тула в каталоге; тесты 18/18 зелёные (`AgentLoopTest` 3 + `AgentLoopApprovalTest` 5 + `ToolRegistryConfigTest` 4 + `UiSnapshotFormatTest` 4 + `NotifyFormatTest` 2); `:app:compileDebugKotlin` — зелёный; `assembleDebug` — BUILD SUCCESSFUL, `app-debug.apk` 72 МБ (11:07:52, демон добежал несмотря на остекленение синхронного вызова — разбор в конце файла). Остаток M2 — ручной чеклист ниже (TalkBack + сценарии на устройстве).
- **Ручной чеклист M2 (на устройстве):**
  - «Найди Сашу и напиши SMS» (контакт→SMS с диалогом подтверждения); отказ/«всегда» работают, повтор не спрашивает; сброс в «Инструменты агента» возвращает вопросы.
  - Тот же сценарий вслепую с TalkBack: диалог читается (название/действие/состояния), у Switch — вкл/выкл.
  - Включить сервис доступности и доступ к уведомлениям (вкладка «Ассистент» показывает «Включён» после возврата); `ui_get_screen` → снимок; `ui_click` по индексу; `notify_list` → список.
  - `web_fetch`: статья (`full`), разведка (`gist`), SPA (`render=always`, пометка `rendered`), блокировка `192.168.x.x`/`localhost`.
- **M2.6 (web_fetch, авточтение веба, 2026-10-04):**
  - Тул `web_fetch {url, mode=gist|full, max_chars?, render=auto|never|always, images?}` (AUTO, read-only) — 24-й в каталоге. В отличие от `web_open`/`web_search` (открывают браузер человеку) возвращает содержимое в контекст модели; их description уточнены («содержимое в модель НЕ возвращается»).
  - Конвейер: Ktor-статика (`WebContentActions`: кап 256 КБ, таймаут 15 с, `Accept: text/markdown`, проба `url+.md` на деградировавшем пути) → собственный скоринг статьи (семантика article/main + плотность, без внешних либ) → свой Markdown-эмиттер (заголовки/списки/ссылки `[t](url)`/код/таблицы/alt, кап 12k + «обрезано»).
  - WebView-tier (`WebRenderActions`): оффскрин 1280×800 на Main, JS вкл, инкогнито-протокол (removeAllCookies+setAcceptCookie(false)+deleteAllData+clearCache, куки браузера пользователя не трогаем по построению), `allowFileAccess=false`, без JS-моста, Mutex один рендер, `destroy()` в finally, `outerHTML.slice(0,500k)` → тот же MD-конвейер. `render=auto`: дорендер если статики <500 символов, пометка tier в ответе.
  - Блок локальной сети (`isBlockedHost`: localhost/127/10/172.16-31/192.168/::1, без DNS-резолва) — защита роутера и будущего MCP на 127.0.0.1.
  - Зависимости: НОЛЬ новых (план был readability4j+jsoup, но Maven Central блокируется прокси 403, а JitPack отдаёт 404/purge по свежим версиям — проверено curl + build-API; `INTERNET` объявлен явно в манифесте, раньше был только транзитивно из okhttp).
  - Тесты 28/28 (`WebMarkdownTest` 7 + `WebFetchPolicyTest` 3 — всё JVM-чистое); `:app:compileDebugKotlin` — зелёный. Рендер юнит-непокрываем (нужен девайс) — ручной чеклист выше.
- **Постфикс web_fetch-ошибок (2026-10-04, по девайс-транскрипту):** в модель и скринридер текли сырые тексты исключений — Ktor `ResponseException` с URL и телом ответа (`traceId`-JSON), `net::ERR_NAME_NOT_RESOLVED` из WebView, `Unable to resolve host`. Теперь чистые `mapFetchError`/`mapRenderError`/`httpStatusMessage` (короткий русский диагноз без URL и тел: 404→«страница не найдена», DNS→«проверьте адрес и подключение», таймаут/TLS/коннект, фолбэк с капом 200 символов); применены в `WebFetchTool` и `WebRender.onReceivedError`. Тест `FetchErrorMappingTest` (5) — зелёный, всего agent 33/33. Кстати, сам транскрипт показал здоровое поведение модели (самокоррекция URL после 404) — чинить там было нечего.
- **Постфикс «вечный стрим» + Стоп (2026-10-04):** агент уходил в бесконечный круг с повтором одного тула — две сцепившиеся баги в `OpenAiCompatibleClient`: (1) `incoming.collect` ждал закрытия соединения сервером, `[DONE]` лишь переходил к следующему событию → при висящем соединении стрим не завершался никогда; (2) фрагменты `tool_calls` (id+name только в первом, дальше куски arguments без id) эмитились отдельными `ToolCall` с битым JSON, а продолжения получали общий id `call_0` → второе ожидание в `ApprovalController` висело вечно. Исправление: `takeWhile(data != [DONE])` (соединение закрываем сами) + страховочный `Finish`, чистый `ToolCallAssembler` (аккумуляция по index, тест `ToolCallAssemblerTest` 5 — зелёный). Кнопка «Отправить» в чате стала «Остановить» во время стрима (`chat_stop` ru/en, отмена снимает стрим/паузу/HTTP; заодно исправлено: раньше `CancellationException` пропускал сброс `isStreaming`). Проверки: provider 31 + agent 28 — всё зелёное, `:app:compileDebugKotlin` — зелёный.
- **Постфикс «цикл одного тула» (2026-10-04, `flashlight` ×5 + лимит):** модель не узнавала результаты своих вызовов — корень в `AgentLoop`: tool-результат клался в историю обычным текстом ассистента `"[tool:name] ..."`, а `ChatMessage/DTO` не знали ролей кроме system/user/assistant. Исправление: `ChatRole.TOOL` + `toolCallId`, `toolCalls: List<ToolCallRef>` у assistant-хода, в DTO — `tool_call_id`/`tool_calls` (пустой контент → null); цикл шлёт пару `assistant(tool_calls=[id])` → `tool(tool_call_id, output)`. Тесты: `AgentLoopToolProtocolTest` (пара сообщений, второго раунда вызовов нет, маркера `[tool:` в истории нет) + `ToolMessageSerializationTest` (2) — зелёные; всего provider 33 + agent 34 = 67/67, `:app:compileDebugKotlin` — зелёный.
- **Постфикс lint (2026-10-04):** релизная сборка (`minifyReleaseWithR8`) гоняет `lintDebug`, который уронил `SuspiciousIndentation` в `AssistActivity.kt:203` (лишний отступ `scope.launch` после правки M2.1). Исправлено, `:feature:assistant:lintDebug` — зелёный. Урок: release-путь включает lint-gate, перед тестовой сборкой прогонять lint изменённых модулей.
- **Постфикс lint-2 (2026-10-04):** `:app:lintDebug` нашёл ещё 4 error: `PropertyEscape` в `gradle.properties:7` (`org.gradle.java.home=C:/…` → `C\:/…`, экранирование properties-совместимо, демон резолвится как раньше) и `local.properties:7` (`sdk.dir` аналогично), плюс `PermissionImpliesUnsupportedChromeOsHardware` на `CALL_PHONE`/`SEND_SMS` → добавлен `<uses-feature android.hardware.telephony required=false/>`. После этого `:app:lintDebug` — зелёный.
- **M2.7 (интент-хвосты, 2026-10-04):**
  - 5 новых тулов (итого 29): `nav_open` (AUTO, `geo:`+chooser, фолбэк на Maps-URL), `share_text` (AUTO, chooser и есть подтверждение), `flashlight` (AUTO, `CAMERA`, проверка вспышки), `media_button` (AUTO, play_pause/next/prev без разрешений), `location_get` (ASK — геоданные sensitive, last-known GPS/Network без Play Services, ответ с точностью/давностью).
  - Манифест: `CAMERA`, `ACCESS_COARSE/FINE_LOCATION`; lint снова потребовал `uses-feature` — добавлен `android.hardware.camera required=false` (гео hardware-implication не даёт). `:app:lintDebug` — зелёный.
  - Исключено обоснованно: секундомер (интента нет в AOSP — проверено по докам и `AlarmClock.java`), `clipboard_get` (Android 10+ режет чтение вне фокуса), громкость/DND/яркость (спецдоступы — M5).
  - Строки ru+en (5 тулов + `perm_camera`/`perm_location`); выдача разрешений — существующими кнопками в карточках. Тесты: новой JVM-покрываемой логики нет (всё на `Context`) — регрессия 33/33; `:app:compileDebugKotlin` + `:app:lintDebug` — зелёные.
  - Релизный гейт снова поймал `MissingPermission` на `getLastKnownLocation` (lint не видит runtime-проверку): точечный `@SuppressLint("MissingPermission")` на `lastLocation()` с комментарием; `:core:agent:lintDebug` — зелёный.
  - Тот же гейт после перехода на свежий фикс: `requestLocationUpdates` живёт в `awaitFreshFix()`, а подавление висело на `freshLocation()` — добавлено туда же; `:core:agent:lintDebug` — зелёный. Урок: `@SuppressLint` не наследуется вызывающими — вешать на функцию с прямым вызовом API.
  - Ручной чеклист M2.7: карты по запросу и по координатам; шаринг-chooser; фонарик вкл/выкл (с выдачей CAMERA); медиа-кнопка на активном плеере; гео с диалогом подтверждения (ACTION в карточке показывает «Спрашивать»).
- **Постфикс «кремлёвская геопозиция» (2026-10-04, девайс-транскрипт):** `location_get` читал `getLastKnownLocation` и отдал 55.7558,37.6176 человеку, который там никогда не был (вероятно VPN-exit/мок/эмулятор — жду проверки устройства; сам факт: кэш произвольной давности + скоринг «новее И точнее» удерживал чужую точку). Исправление: кэш удалён целиком — `freshLocation()` (GPS+network параллельно, победа ≤50 м, к таймауту лучший свежий не грубее 500 м, иначе честная ошибка с guidance), ответ `Координаты: lat,lng (точность N м, свежий фикс, провайдер[, mock])` (+`Location.isMock` с `@Suppress(DEPRECATION)`). Тест `LocationFormatTest` (2) — зелёный.
- **Тул `device_state` (2026-10-04, итого 30):** живой снимок одной строкой `torch=off; music=…; wifi=…; battery=78%+; screen=…; ringer=…` (AUTO, без runtime-разрешений; `ACCESS_WIFI_STATE` — normal, только манифест, lint чист). Фонарик: query-API у `CameraManager` нет — `TorchTracker` (in-memory `ConcurrentHashMap` + `registerTorchCallback` в `AppGraph.init`, свои переключения пишутся синхронно; после смерти процесса — честный `unknown`, персист осознанно не делаем). Батарея — sticky-интент, экран — `PowerManager`, звук — `ringerMode`, музыка — `isMusicActive`. Карточка в настройках (ru/en). Тесты `DeviceStateTest` (4: формат, unknown, трекер) — зелёные; всего agent 40/40; `:app:compileDebugKotlin` + `:app:lintDebug` — зелёные.
- **Ручной чеклист гео+статуса:** на улице — GPS-фикс ≤50 м за ≤20 с; в помещении без GPS — честная ошибка (не Кремль); `device_state` после «включи фонарик» показывает `torch=on`; Стоп посреди ожидания гео — слушатель снят, повтор работает.
- **M0 (базовый каркас):** мультипроект Gradle, `AppGraph`, Compose-навигация Чат/Ассистент/Настройки, `ShareActivity`, тема M3 light+dark, `core/a11y`, строки ru+en, выбор языка, Room/DataStore, `SecretStore`+`KeystoreAead`, redacting-логгер, `allowBackup=false`.
- **M1.1 (модель провайдеров):** `ProviderProfile`/`ModeConfig`/`ModelInfo`/`ModelCatalog`, `ProviderConfigStore`, `ProviderRepository` (alias-схема секретов). Тесты: `ProviderConfigCodecTest` — зелёные.
- **M1.3 (сеть/провайдер):** `core/net` (Ktor HttpClientFactory, RedactingHttpLogger, SSE), `core/provider` (ModelClient, OpenAiCompatibleClient, DTO, ProviderAuth, ProviderError). Тесты: `OpenAiChunkParsingTest` — зелёные.
- **M1.2 (UI провайдеров):** `ProviderListScreen`, `ProviderEditScreen` (API-key mask/enabled), `ModeConfigScreen` (provider dropdown/model picker с fetchModels/prompt per-режим), навигация, строки ru+en. API-ключ сохраняется через `ProviderRepository.setApiKey`. Пикер моделей: dropdown + «Обновить список» через `modelClientFor().fetchModels()` + ручной ввод.
- **M1.4 (AgentLoop + Tools):** `core/agent` — `AgentLoop` (stream + tool execution), `ToolRegistry`, `Tool` interface, `DeviceActions` (интенты). Инструменты: `app_launch`, `web_open`, `web_search`, `alarm_set`, `open_settings`. `ChatChunk.ToolCall` для стриминга вызовов. Тесты: `AgentLoopTest` (3) — зелёные.
- **M1.5 (Чат baseline):** `ChatScreen` со стримингом (`sendMessage: (String, List<ChatMessage>) -> Flow<ChatChunk>`), подключён к `AgentLoop` через `AppGraph.modelClientFor`.
- **M1.9 (ShareActivity):** приём текста/изображений/файлов → `AgentFactory` → `AgentLoop` → стрим ответа. `AgentFactory` (bridge из `core:agent` в `app`), `SharePayload` (парсинг intent), UI: контент + вопрос + ответ.
- **M1.7 (AssistActivity):** обработка `ACTION_ASSIST`/`VOICE_COMMAND`, извлечение контекста экрана из `AssistStructure`, оверлей с полем вопроса + стрим ответа, тема `Theme.OAssist.Assist` (translucent).
- **M1.6 (VIS-стек):** `VoiceInteractionService` + `VoiceInteractionSessionService` + `RecognitionService`-стаб, `@xml/voice_interaction_service` + `@xml/recognition_service`, манифест-регистрация. Контекст экрана из `AssistStructure` передаётся в агента.
- **M1.8 (Речь):** `core/speech` — `SpeechOutput` (OFF/SYSTEM_TTS/SCREEN_READER, разбиение на предложения), `Narrator` (timeline tool calls/thoughts, events для announceForAccessibility). Тесты: `NarratorTest` (4) — зелёные.
- **M1.10 (Провайдеры: выбор моделей, валидация, OpenCode):**
  - Lenient-парсинг ответов: `ProviderJson` (ignoreUnknownKeys) вместо строгого `Json.Default` в `OpenAiCompatibleClient` — ответы с `created`/`cost`/`limit` больше не валят парсинг.
  - `ProviderError` (отдельный файл): `from(Throwable)` (Http/Network/Protocol/NotSupported/InvalidModel/InvalidInput) + `userMessage(context)` — ресурсы ru/en в `core/provider`, тела/секреты в текст не попадают.
  - Обратная связь «Обновить список моделей» — Toast (успех «Модели загружены: N», ошибка с причиной, «Провайдер не найден»); пустое состояние в дропдауне моделей; refresh идёт по **выбранному** в UI провайдеру (не по сохранённому `config.providerId`); каталог персистится через `saveModelCatalog`/`observeModelCatalog`.
  - Валидация провайдера при сохранении: `ProviderValidator` (local: имя/Base URL → `InvalidInput`; сеть: `fetchModels()` с подстановкой несохранённых ключей через `ProviderAuth`-override, таймаут пробы 20 с). ~~ANTHROPIC/GEMINI — заглушка `Skipped`~~ → устарело в M3.1/M3.2: `Skipped` недостижим, все 4 типа валятся реальной пробой. Успех → сохранение + закрытие; ошибка → `AlertDialog` («Не удалось сохранить провайдера» + `userMessage`), форма остаётся. UI: `isSaving`-спиннер, `ProviderSecretsInput` (apiKey/BasicAuth + clear-флаги).
  - Тип `OPENCODE_ZEN` (бывший `OPENCODE`): OpenCode Zen / Go — OpenAI-совместимый протокол (`GET {base}/models`, `POST {base}/chat/completions`) + обязательные заголовки `x-opencode-session` (стабильный на диалог — детерминированный UUID от system+первого user-вопроса, греет prompt cache Zen) и `x-opencode-request: msg_<uuid>` (свежий на запрос). Клиент `opencode serve` (`OpenCodeClient`, Basic Auth, `/provider`) — **удалён, не нужен**. Миграция: легаси `"OPENCODE"` в DataStore читается как `OPENCODE_ZEN` (`ProviderKindSerializer`). Тип `OPENAI_COMPAT` — для прочих OpenAI-совместимых API (`GET {base}/models`).
  - Подписи к типам провайдера в `ProviderEditScreen` (OPENAI_COMPAT ≠ OPENCODE_ZEN).
  - Тесты: `OpenCodeZenHeadersTest` (заголовки Zen, стабильность session id), `ProviderErrorMappingTest`, `ProviderValidatorTest`, миграция `OPENCODE`→`OPENCODE_ZEN` в `ProviderConfigCodecTest` — зелёные. `ktor-client-mock` добавлен в version catalog.
- **Фактический JDK:** `c:\dev\jdk\jdk-21.0.10+7`; Gradle Wrapper: 9.4.1.
- **Стратегия проверок:** точечные таски (`compileDebugKotlin` + `testDebugUnitTest`), `assembleDebug` — только для финальной верификации. **Юнит-тесты — только с `--no-daemon`.** Без него прогоны виснут/убиваются harness'ом (`ChildProcess.kill`, прецедент 2026-10-04: `testDebugUnitTest` без флага умирал, с флагом — зелёный). Запрет импровизации `--offline`/`--no-build-cache` остаётся в силе; `--no-daemon` — не импровизация, а обязательный флаг для тестов (и допустим для `assembleDebug`).- **Обвязка Gradle закалена (2026-10-04, vs вис test-прогонов):** `gradle.properties` — `org.gradle.java.home=C:/dev/jdk/jdk-21.0.10+7`, `org.gradle.console=plain`, `-XX:+HeapDumpOnOutOfMemoryError`, `daemon.idletimeout=3600000`; создан `local.properties` (`sdk.dir`, в gitignore). `gradlew help` после смены — BUILD SUCCESSFUL за 13 с, новый демон IDLE. Отложено: `kotlin.incremental=false` / `in-process` / `forkEvery` — только при рецидиве зависаний, после треддампа `GradleWorkerMain`. Урок: фоновый `Start-Process` внутри инструментального вызова убивается harness'ом (`ChildProcess.kill`, демон при этом выживает) — агентские прогоны делать синхронно с явным таймаутом, фон+poll только для ручных запусков.
- **Разбор остекленения сессии 2026-10-04 (по `nextup.json`):** зависший вызов — `:app:assembleDebug --console=plain --offline --no-build-cache` (таймаут 600 с, `state=running`, вывод встал на `:app:processDebugManifestForPackage`). Демон здоров: 11 `Received` = 11 `Finishing`, IDLE после, APK 72 МБ от 11:07:52 на месте; повтор без флагов — BUILD SUCCESSFUL за 6 с. Вывод: вис — на стороне синхронного harness-вызова (сессия остекленела целиком), не Gradle. Запрет импровизации `--offline`/`--no-build-cache` внесён в `AGENTS.md`. Та же сессия: агент дёргал `--stop` и `cleanTest` посреди работы — так больше не делать.
- **M3.1 (Anthropic native, 2026-10-04):**
  - `core/provider/anthropic/`: `AnthropicClient` (`GET {base}/v1/models` + `x-api-key`/`anthropic-version: 2023-06-01`, `POST {base}/v1/messages` SSE), `AnthropicMapper` (SYSTEM→`system`, TOOL→`tool_result`, assistant→`tool_use`, `ToolSchemaDto`→`input_schema`), `AnthropicStreamAccumulator` (склейка `input_json_delta` по index, как `ToolCallAssembler`). Хвост `/v1` в base терпится без удвоения.
  - `ModelClient.create` — ветка ANTHROPIC; `ProviderValidator` больше не `Skipped` для него; `ProviderError.NotSupported` остался только для GEMINI (потом убран вовсе).
  - Тесты 17 (`Accumulator` 5 + `Mapper` 9 + `Client` 3: заголовки, нормализация URL, label).
- **M3.2 (Gemini native, 2026-10-04):**
  - `core/provider/gemini/`: `GeminiClient` (`GET {base}/v1beta/models` + `x-goog-api-key`, `POST models/{id}:streamGenerateContent?alt=sse`), `GeminiMapper` (SYSTEM→`system_instruction`, ASSISTANT→роль `model`, TOOL→`functionResponse` с восстановлением **имени** по id из соседних ходов — `functionResponse` требует name, а `ChatMessage.TOOL` несёт только id; неизвестный id — fail-closed), `extractGeminiChunk` (текст сразу, `functionCall` целиком с синтетическим `call_<n>` — кускования args у Gemini нет).
  - `ModelInfo.id` — без префикса `models/` (генерация re-adds), capability из листинга честно пустые (`supportedGenerationMethods` про тулы не говорит).
  - `Skipped` в валидаторе убран полностью (sealed object оставлен, недостижим); `NotSupported` мёртв, класс оставлен.
  - Тесты 14 (`Mapper` 11 + `Client` 3). Всего provider 64/64; `:app:compileDebugKotlin` + `:core:provider:lintDebug` — зелёные.
- **M3.3 (capability-проба, 2026-10-04):**
  - `ModelClient.probeCapabilities(modelId)` (дефолт `emptySet` — фейки агента не трогаем): дешёвый нестримовый запрос с фиктивным тулом; 200 = `TOOLS`, 400 = пусто (сигнал, не ошибка), остальное — исключение. VISION/AUDIO_IN/REASONING — только вручную.
  - `ProviderRepository.updateModelCapabilities` + чистый `patchModelCapabilities` (модель вне каталога добавляется — ручной ввод) + тест (3).
  - UI `ModeConfigScreen`: секция «Возможности модели» (4 Switch + `state_enabled/disabled`, кнопка «Проверить» со спиннером, хинт); `MainActivity`: `isProbing`, тосты `models_probe_tools/no_tools`, обновление списка из каталога. Строки ru+en (`mode_caps_title`, `mode_probe(+_hint)`, `cap_*`).
  - Тесты проб: `OpenAiProbeTest` 2 + по 2 в Anthropic/Gemini-клиентах. Lint `:feature:settings` + `:app` — зелёные; регресс `:core:agent` — зелёный.
- **M3.4a (MCP-клиент core, 2026-10-04):**
  - `core/model/Mcp.kt`: `McpServerConfig` (id/name/url/bearerRef/extraHeaders/enabled, секреты — alias).
  - `core/data/mcp/`: `McpConfigStore` (DataStore `mcp_config`) + `McpServerRepository` (CRUD + `set/clearBearer` + `resolveHeaders`, удаление сносит секреты); `DataContainer.mcpServers`.
  - `core/agent/mcp/`: `McpClient` (Streamable HTTP: `initialize`→`tools/list`→`tools/call`, `mcp-session-id`, stateless тоже; plain JSON или SSE-`data`, свой `McpJson` — общий `ProviderJson` internal для модуля), `McpTool` (риск ASK fail-closed, имя `mcp_<8id>__<имя>` строго `^[a-zA-Z0-9_-]{1,64}` — только ASCII, `isLetterOrDigit` пропускает кириллицу; описание с именем сервера, кап 500; вывод кап 8000 + «обрезано»).
  - `ToolCatalog.createRegistry(..., externalTools)` (коллизия имён — встроенные в приоритете); `AppGraph.agentFor` резолвит включённые серверы (`resolveHeaders` + `listTools`, таймаут 20 с на сервер, упавший — пропуск без падения агента).
  - `core/agent/build.gradle.kts`: + `kotlin.serialization` plugin (без него `@Serializable` в agent не компилируется — поймано демоном после `ChildProcess.kill`) + `ktor-client-core`/`mock` (кэшированные артефакты, новых загрузок нет).
  - Тесты 16 (`McpDtoParsingTest` 6 + `McpToolNameTest` 5 + `McpClientTest` 5: handshake/листинг/вызов/ASK/isError). Всего agent 56/56.
  - Инфра: рецидив лока `core/model/.../classes.jar` (3 повтора) — держал KotlinCompileDaemon с 10:49; пользователь подтвердил верификацию (Studio закрыта, демон IDLE, чужих билдов нет) → штатный `gradlew --stop` по AGENTS.md, лок ушёл. Урок: `headersOf` с 4 строками не компилируется — только пары (`"K" to listOf("V")`).
- **M3.4b (UI MCP + BasicAuth, 2026-10-04):**
  - `McpServersScreen` (список с вкл-Switch + редактор: имя/URL/Bearer-маска/«Заменить»/«Удалить»/«Проверить»/удаление; новый id `m<millis>`), маршрут `mcp_servers`, секция в настройках; тест соединения — тосты `mcp_test_success/error`. Строки ru+en (`mcp_*`).
  - BasicAuth: поля только для OPENCODE_ZEN (`provider_basic_user/password` ru+en, маска), `ProviderSecretsInput` + `saveProvider` (`set/clearBasicAuth`), **клиент**: `fromProfile` резолвит basic-пару, `applyAuth` шлёт `Basic base64(u:p)` вместо Bearer (ровно один `Authorization`). Тест приоритета Basic.
  - `app/build.gradle.kts`: + `kotlinx.serialization.json` (AppGraph трогает `JsonObject` из agent-API).
  - Финал `--no-daemon` (по просьбе пользователя): свип 155/155 (model 5 + a11y 4 + data 15 + provider 71 + agent 56 + speech 4), `:app:assembleDebug` BUILD SUCCESSFUL, APK 69,6 МБ.
- **Ручной чеклист M3 (на устройстве):**
  - Anthropic: ключ → «Обновить список» → модели с подписями; чат с тулом (`device_state`) работает; неверный ключ — русский диагноз, форма остаётся.
  - Gemini: то же; `x-goog-api-key` идёт заголовком, не query.
  - Capability: выбрать модель → Switch показывают пустоту → «Проверить» → тост «поддерживает инструменты» + Switch TOOLS вкл; ручной Switch VISION сохраняется после перезахода.
  - MCP: добавить сервер (URL + Bearer) → «Проверить» → «инструментов: N» → чат вызывает `mcp_*` тул с диалогом ASK; выкл. сервер — тулы пропадают; удаление сносит Bearer из Keystore.
  - Zen+Basic: свой сервер за логином/паролем → список грузится, чат идёт.
- **M4.1 (вывод речи без ONLINE_TTS, 2026-10-04):**
  - `core/speech/SentenceSplitter` (pure: `splitSentences`/`completePrefix`, граница `(?<=[.!?…])\s+`) + тест `SentenceSplitterTest` (9). Всего speech 13/13.
  - `SpeechOutput` переписан: дубликат `SpeechOutputChannel` удалён (единый из `core/model`, включая отложенный `ONLINE_TTS` → пропуск с логом); `runBlocking` убит целиком (speak — TTS `QUEUE_ADD` / анонсер `tryEmit`); смена канала — кастомный сеттер `channel` (стоп + ленивый init, очередь не теряется); локаль `forLanguageTag("ru-RU")`; анонсер `FlowAnnouncer` инжектится (SCREEN_READER — скрытый live-узел). Урок: `var x` + `fun setX` = platform clash — только кастомный сеттер.
  - `SpeechHandle` (registry как `AgentFactory`) — мост речи для `feature:*` без зависимости от `app`; `core/speech` + `implementation(core:a11y)`; `feature/chat`+`feature/assistant` + `implementation(core:speech)`.
  - `core/data/prefs/SpeechSettingsStore` (DataStore `speech_settings`, device-protected) + `DataContainer.speechSettings`; `AppGraph.applySpeechSettings` + регистрация `SpeechHandle`; `MainActivity` подписывается при старте/смене.
  - `AgentEvents` (tool call/result, suspend, дефолт no-op) + хук в `AgentLoop`; `AppGraph.narratorEvents` → `Narrator`; чат и `agentFor` передают; нарратив коллектится в `MainActivity` → анонсер (`narrator_tool_call/done` ru+en, результат целиком не зачитываем).
  - UI: секция «Речь» в настройках (каналы OFF/SYSTEM_TTS/SCREEN_READER — ONLINE_TTS скрыт как отложенный + тумблер нарратива, `speech_*` ru+en); чат — по-предложенческая озвучка стрима + хвост на Finish + Стоп/barge-in + «Озвучить ответ» (`CustomAccessibilityAction`) + сворачиваемый timeline (последние 20, Thought/ToolCall/ToolResult); видимый текст больше НЕ live region (и в чате, и в оверлее Assist); Assist — автоозвучка по `AssistantExtras.autoSpeak`, стоп при закрытии/новом вопросе, `assistant_speak_answer` ru+en.
  - Проверки: speech 13 + agent 56 — зелёные (`--no-daemon`); `:app:compileDebugKotlin` + lint 7 модулей (`app/chat/settings/assistant/speech/agent/data`) — зелёные. `assembleDebug` не гоняли (не финал Milestone).
- **Ручной чеклист M4.1 (на устройстве):**
  - Канал SYSTEM_TTS: ответ договаривается по предложениям прямо во время стрима; Стоп гасит и стрим, и звук; новый вопрос прерывает озвучку (barge-in).
  - Канал SCREEN_READER с включённым TalkBack: предложения читаются по мере готовности, видимый текст не перечитывается целиком; действие «Озвучить ответ» повторяет готовый ответ.
  - Канал OFF: тишина, стрим идёт как раньше.
  - Смена канала посреди ответа: уже поставленное договаривается, новое идёт в новый канал, потерь/дублей нет.
  - Настройки речи переживают перезапуск; тумблер нарратива вкл/выкл; тул-вызов в чате (`device_state`) даёт «Выполняю/Готово» в скринридере + запись в timeline; TalkBack-проход по секции «Речь» и timeline (имена/состояния вкл-выкл/развёрнут-свёрнут).
- **M4.2 (TTS на режимы + озвучка тулов комментарием, 2026-10-04):**
  - `core/model`: новый `TtsConfig` (channel/rate/pitch/volume/voiceName/speakToolCalls=true/narrative=true + константы диапазонов + `DEFAULT`); `ModeConfig.tts: TtsConfig?` (null = дефолт, старые JSON читаются); `effectiveTts()`; мёртвый `AssistantExtras.narrative` удалён (JSON-безопасно).
  - Метка комментария: `ChatChunk.Content.isToolComment=false` (дефолт держит 22 места сборки); `AgentLoop` ставит `true` тексту перед тул-чунками; тест в `AgentLoopToolProtocolTest`.
  - `SpeechOutput.applyConfig` (канал+rate/pitch/volume+голос; громкость — `KEY_PARAM_VOLUME` на фразу; голос из `tts.voices`, мимо — дефолт); `availableVoices()` (suspend, таймаут 5 с, `VoiceInfo` pure); готовность — `StateFlow` вместо флага. Урок тестов: `completePrefix` требовал пробел после точки, а `AgentLoop` отдаёт текст целым — комментарий молчал бы и в проде; теперь хвост с терминальной пунктуацией считается готовым.
  - `StreamSpeaker` (pure, один на чат/ассист/шаринг): готовые предложения сразу; коммент — только при `speakToolCalls` (счётчик всё равно продвигается, иначе вылезет в хвосте); `[tool]`/`[tool: result]` не говорятся никогда (`stripServiceLines`); хвост на Finish; коммент кап ~500 символов по предложениям. Тест `StreamSpeakerTest` (7) + `StripServiceLinesTest`.
  - Глобальная «Речь» удалена целиком (`SpeechSettingsStore`, секция настроек, `speech_*`-строки; сиротский DataStore-файл безвреден, миграции нет — дефолты режимов покрывают). Источник истины — `ModeConfig.tts`; применяется перед каждым стримом (`applyModeTts` + `SpeechHandle.applyTts/setNarrativeEnabled` для Assist/Share).
  - UI: секция «Озвучка» в `ModeConfigScreen` (канал-радио, дропдаун голосов через `loadVoices` из app, 3 слайдера, 2 Switch, `mode_tts_*` ru+en); чат — `speakToolCalls` из конфига; Assist — гейт `autoSpeak` + tts; Share — новая минимальная обвязка речи + стоп при закрытии, видимый текст больше не live region (там был тавтологический `if (isStreaming) Polite else Polite`).
  - Проверки: speech 21 + agent 57 + data 17 + provider 71 — зелёные (`--no-daemon`); `:app:compileDebugKotlin` + lint 9 модулей — зелёные. `assembleDebug` не гоняли.
- **Ручной чеклист M4.2 (на устройстве):**
  - В каждом режиме свой канал/голос/тембр: чат OFF + ассистент TTS + шаринг скринридер — независимо; настройки переживают перезапуск; старые конфиги без `tts` работают с дефолтами.
  - Скорость/тон/громкость слышно меняют голос; смена голоса применяется без падений.
  - Тул-вызов с комментарием («ищу контакт…»): тумблер вкл — комментарий звучит, тип тула не звучит нигде; тумблер выкл — тихо, финальный ответ звучит.
  - TalkBack-проход по секции «Озвучка» (слайдеры объявляют значения, дропдаун голоса читаем, Switch вкл/выкл).
- **M4.3 (системный STT + hands-free, 2026-10-04):**
  - Только системный ввод: кнопка микрофона в чате и оверлее Assist (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, `FREE_FORM`, `ru-RU`, `EXTRA_PROMPT`); распознанное сразу уходит агенту (минуя поле ввода), нераспознанное молча игнорируется. Без распознавателя в системе — русский тост, без разрешения — явная подсказка (не молча). Тап микрофона во время стрима — barge-in (стоп стрим+озвучка, затем слушаем).
  - Манифест: `RECORD_AUDIO` + `uses-feature microphone required=false`. `OAssistRecognitionService` — честный стаб (`ERROR_CLIENT` вместо пустых методов): нужен только `RoleManager` для `ROLE_ASSISTANT`.
  - Hands-free цикл: вопрос голосом → ответ звучит сам (гейт `autoSpeak` в Assist; в чате — по каналу режима). В чате отправка вынесена в локальную `send()` (переиспользуется mic-результатом); в Assist — в `ask()` (поле очищается, `response` сбрасывается).
  - Зависимости: `feature/chat` + `activity-compose`. Урок: локальные `fun` в Composable требуют текстового порядка объявления — цепочка `send/ask → recognizerLauncher → launchRecognizer → micPermissionLauncher → onMicClick`.
  - Новой JVM-покрываемой логики нет (всё на `Context`/лаунчерах) — только регресс компиляцией. Строки `chat_mic*/assistant_mic*` ru+en.
  - Проверки: `:feature:chat + :feature:assistant + :app:compileDebugKotlin` + lint 3 модулей — зелёные. `assembleDebug` не гоняли.
- **Ручной чеклист M4.3 (на устройстве):**
  - Mic в чате: первая выдача `RECORD_AUDIO` системным диалогом; отказ — тост-подсказка; распознанный вопрос уходит агенту сам; на устройстве без распознавателя — тост (не падение).
  - Hands-free в Assist: вопрос голосом → ответ озвучивается (при `autoSpeak`); тап микрофона посреди озвучки прерывает её и слушает заново.
  - TalkBack-проход: у кнопки имя «Голосовой ввод», touch target ≥48dp (через `oassistTouchTarget`).
- **M4.4 (PTT + аудио в модель + голосовые тулы, 2026-10-04):**
  - Ответ на вопрос «узнать фичи модели»: полностью авто — нет (списки провайдеров capabilities не отдают; пробой ловится только TOOLS). `AUDIO_IN` — только ручной Switch, он же гейт аудио-ветки. Без флага — молча в STT.
  - `core/model`: `SttEngine {SYSTEM, MODEL_AUDIO}` + `SttConfig(engine, language=ru-RU)`; `ModeConfig.stt` + `effectiveStt()` (старые JSON читаются); секция «Голосовой ввод» в `ModeConfigScreen` (`mode_stt_*` ru+en).
  - Аудио-пламбинг: `AudioPart(wavBase64/mime/durationSec)` (строкой, не ByteArray — цел equals истории); `ChatMessage.audio`; `AgentLoop.stream(..., audio)`; OpenAI — `content: String?→JsonElement?` + parts `text/input_audio(wav)` (`toDto` internal для тестов); Gemini — `GeminiPartDto.inlineData`; Anthropic — fail-closed `NotSupported("audio input")` (API не умеет).
  - Захват (`core/speech`): `WavEncoder` (WAV 16 кГц моно + тест), `VoiceCaptureSession` (pure guard idle/listening + тест), `VoiceListener` (STT-обёртка SpeechRecognizer с partials + RAW AudioRecord-поток, кап 120 с, автостоп; `CaptureError/SttError` кодами — локализация в UI/тулах), `VoiceHandle` (registry для UI) + `VoiceCaptureController`/`AgentVoiceCapture` (STT-режим для тулов, главный поток, сторожевые таймауты, `SILENCE`-маркер).
  - Чат: пустой Send = холд (`detectTapGestures.onPress` + `tryAwaitRelease`, работает и TalkBack long-press+hold); RAW при `MODEL_AUDIO`+флаг, иначе STT с живыми partials в поле; отпуск <0.5 с — отмена с хинтом; `send(text, audio)` + аудио в истории; пузырь — плейсхолдер `chat_voice_message`; иконка Send↔Mic, статус «Слушаю…» (уместный live region). Mic-кнопка — toggle-альтернатива. `MainActivity`: `stt`/`audioIn` (флаг из каталога) + `sendMessage` с аудио.
  - Assist: mic-тап через тот же in-app захват (до тишины/повторного тапа, partials в поле, финал в `ask()`); `RecognizerIntent`-путь удалён целиком; язык из SttConfig режима.
  - Тулы `voice_listen_start` (ASK — включение микрофона прайваси-чувствительно, `RECORD_AUDIO` в `requiredPermissions`, `describeForConfirmation`) / `voice_listen_stop` (AUTO, возвращает транскрипт/диагноз); транскрипт агент читает из tool-хода — `AgentLoop` не менялся. Карточки в настройках (`tool_voice_*`, `perm_microphone` ru+en).
  - Уроки: локальные `fun` в Composable — только текстовый порядок (цепочка send→launcher→…); `companion object` внутри `@Composable` незаконен (константа — на уровень файла); lint `MissingPermission` на AudioRecord лечится точечным `@SuppressLint` (прецедент гео M2.7); `ChatMessageDto.content` полиморфен — конструкторы со строками оборачивать в `JsonPrimitive`.
  - Проверки: speech + agent + data + provider — зелёные (`--no-daemon`); `:app:compileDebugKotlin` + lint 9 модулей — зелёные. `assembleDebug` не гоняли.
- **Ручной чеклист M4.4 (на устройстве):**
  - Холд Send в чате (STT-режим): partials в поле, отпуск — текст уходит; холд <0.5 с — хинт, не отправка; тап Mic — toggle слушать/стоп.
  - MODEL_AUDIO + флаг AUDIO_IN у модели: отпуск холда — аудио уходит (пузырь «Голосовое сообщение (N с)»), ответ по аудио; без флага при том же движке — молча STT-текст.
  - Тулы: агент спрашивает вслух → `voice_listen_start` (ASK-диалог) → ответ голосом → `voice_listen_stop` → текст в контексте; тишина — `(тишина)`; таймаут — честный диагноз; отмена пользователем.
  - TalkBack: пустой Send объявляет «Удерживайте кнопку, чтобы говорить», long-press+hold пишет, отпуск отправляет; «Слушаю…» объявляется.
- **Открытые вопросы (добавилось):**
  - Раздувание истории base64 при длинных аудио-диалогах: кап 120 с смягчает; фолбэк — резать аудио из старых ходов истории (оставить последний голосовой ход).
- **M4.5 (выбор движка TTS + голоса по языкам, 2026-10-04):**
  - `core/model`: `TtsConfig.enginePackage: String?` (null = системный по умолчанию; голос теперь в скоупе движка); старые конфиги читаются, миграция не нужна.
  - `core/speech`: `TtsEngineInfo` + `VoiceInfo(enginePackage/quality/requiresNetwork)`; `availableEngines()` (дефолт первым), `availableVoices(pkg?)` (чужой движок — временный инстанс + shutdown в finally, снесённый пакет — пусто); `applyConfig` пересоздаёт инстанс при смене пакета (setEngineByPackageName deprecated); `voiceLanguageTags()` pure + тест. Урок: `getEngines/getDefaultEngine` — инстансные методы, не статические (проверено javap по android-36); дефолт читаем из `Settings.Secure.TTS_DEFAULT_SYNTH`, известность пакета — через query TTS-сервисов.
  - UI: дропдауны движок → язык → голос (`loadEngines` + `loadVoices(pkg)` из app, ленивая загрузка голосов только выбранного движка); смена движка сбрасывает голос; сетевые голоса с пометкой `(сеть)`; имена языков — платформенные `Locale.getDisplayName` (`mode_tts_engine/language/*` ru+en).
  - Проверки: speech + data — зелёные (`--no-daemon`); `:app:compileDebugKotlin` + lint 4 модулей — зелёные. `assembleDebug` не гоняли.
- **Ручной чеклист M4.5 (на устройстве, нужно 2+ движка):**
  - Все установленные движки в списке с именами, дефолтный помечен; у режимов разные движки — независимо; выбор переживает перезапуск.
  - Языки → голоса внутри, сетевой голос с пометкой; снесённый после выбора движок — тихий фолбэк на голос по умолчанию.
  - TalkBack-проход по трём дропдаунам.
  - **Верифицировано на устройстве (2026-10-04): движки видны после `<queries>`-фикса, TTS закрыт как успешный.**
- **M4.7 (озвучка VIS-сессии + финал M4, 2026-10-05):**
  - Дыра: настоящий assist-жест (`OAssistVoiceInteractionSession`) показывал текст молча — речи не было вовсе (проверено grep: ноль обращений). Закрыто по образцу Assist-оверлея: применение `ASSISTANT`-tts перед стримом (`AppGraph.applyModeTts`, напрямую — сессия в app-модуле), гейт `autoSpeak`, `StreamSpeaker` с `speakToolCalls`, стоп в `onHide()` + barge-in в `onHandleAssist`.
  - Нюанс: в окне сессии нет `AnnouncerHost`, поэтому при канале SCREEN_READER — одно `announceForAccessibility(полный_ответ)` на Finish вместо потока (иначе анонсы уходили бы в никуда).
  - Проверки: `:app:compileDebugKotlin` + `:app:lintDebug` — зелёные; финальный `:app:assembleDebug` дефолтными флагами — BUILD SUCCESSFUL, APK 70 МБ.
  - Отложено (follow-up, не M4): нарратив «Выполняю/Готово» в жесте может потеряться, если MainActivity мертва (коллектор в её композиции); ответ вслух работает независимо.
- **M4 (код) — ЗАВЕРШЁН (2026-10-05).** Отложены решением: ONLINE_TTS (заглушка с логом), кастомный HTTP STT (только системный). Открыто: обрезка base64 из старых ходов истории; device-чеклисты M4.1–M4.4/M4.6/M4.7 — на устройстве.
- **M4 — ЗАКРЫТ (2026-10-05). Верифицировано на устройстве: базово всё работает.** Онлайн-синтез (ONLINE_TTS) и онлайн-распознавание речи — откинуты, держим в планах как не приоритетное. Нарратив в assist-жесте — вариант B (не чинить, ассистенту важен только результат). Следующие темы: упрощение окна ассистента, планирование M5.
- **Ручной чеклист M4.7 + финал M4 (на устройстве):**
  - Assist-жест → текст стримится + ответ звучит (SYSTEM_TTS, autoSpeak вкл); autoSpeak выкл → только текст.
  - SCREEN_READER + TalkBack → ответ объявляется целиком по готовности, дублей нет.
  - Второй жест посреди озвучки — прерывает и начинает заново; скрытие панели — тишина.
  - Сквозное по M4: hands-free диалог (вопрос голосом → ответ звучит сам); смена канала TTS ↔ скринридер без потери очереди предложений.
- **M4.6 (память + скиллы + редактор промптов, 2026-10-04):**
  - Дефолтных промптов не было вовсе (null = без промпта) — созданы `app/assets/prompts/{ru,en}/{chat,assistant,share}.md` с плейсхолдерами.
  - `core/model`: `SkillInfo` (name/title/body/enabled + `NAME_PATTERN`); `AssistantExtras.narrative` уже удалён ранее.
  - `core/data`: `MemoryStore` (интерфейс + `FileMemoryStore`, `MEMORY.md`, device-protected) + `SkillStore` (интерфейс + `FileSkillStore`, `skills/<name>/SKILL.md`, вкл-выкл в DataStore `skill_config`); `DataContainer.memory/skills`. Интерфейсы — ради фейков в тестах тулов.
  - `core/agent`: `PromptRenderer` (pure: `{locale} {time} {device} {memory} {skills} {screen_context} {shared_content}`, капы 8k/4k-навык/12k-все + явные пометки обрезки ru/en) + тест `PromptRendererTest` (7); тулы `memory_get` (AUTO) / `memory_put` / `memory_forget` (ASK, fail-closed без query) / `skill_list` / `skill_get` (AUTO) + тесты на фейках; `ToolCatalog` += 5 дескрипторов и параметры (memory, skills).
  - `AgentFactory.systemPromptFor(mode, screenContext?, sharedContent?)` — единая точка (было 4 ручные сборки); `AppGraph.buildSystemPrompt` (шаблон свой/asset по языку AppLanguage, память, включённые скиллы, время/девайс); все 4 точки (чат, Assist, Share, VIS) переведены.
  - UI: секции «Навыки»/«Память» в настройках + маршруты `skills`/`memory`; `SkillsScreen` (список, Switch, CRUD-диалог с валидацией имени, удаление с подтверждением); `MemoryScreen` (редактор + предупреждение про секреты + стирание с подтверждением); `ModeConfigScreen` — хинт плейсхолдеров + экстры ASSISTANT (3 Switch) / SHARE (поле+Switch); карточки тулов (`tool_memory_*/tool_skill_*` ru+en). Всё ru+en.
  - Проверки: свип 6 модулей (model/a11y/data/provider/agent/speech) + `:app:compileDebugKotlin` + lint 8 модулей — зелёные. `assembleDebug` не гоняли.
- **Ручной чеклист M4.6 (на устройстве):**
  - «Запомни, что я люблю чай» → ASK-диалог → `MEMORY.md` в настройках содержит факт; следующий диалог учитывает (видно по ответу); `memory_forget` чистит; стирание всего — только из настроек.
  - Новый скилл через UI (имя/заголовок/текст) → выкл → в промпте не участвует; вкл → модель следует инструкции; `skill_get` отдаёт тело.
  - Свой initialPrompt с плейсхолдерами работает; пустой — дефолт из assets; экстры ASSISTANT/SHARE сохраняются и действуют.
  - TalkBack-проход по экранам «Навыки»/«Память» и секции промпта (диалоги читаются, Switch вкл/выкл).
- **Постфикс M4.5 (2026-10-04, девайс-транскрипт):** в списке движков были только системный + Google, сторонние синтезаторы пропали. Причина — package visibility (API 30+): без `<queries>` на `TTS_SERVICE` и `getEngines()`, и `queryIntentServices` видят только системные пакеты. Лечение — блок `<queries><intent><action TTS_SERVICE/></intent></queries>` в манифесте (проверки: `:app:lintDebug` + `:app:assembleDebug` дефолтными флагами, APK 69,8 МБ). Урок: любой discovery через PackageManager/implicit intent на 30+ требует `<queries>` — проверять сразу, не на устройстве.

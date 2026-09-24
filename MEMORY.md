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

- **Фаза:** M0 — каркас + a11y-фундамент + `SecretStore`.
- Сделано: `opencode.json`, `AGENTS.md`, `MEMORY.md`, `.gitignore`, git-репозиторий.
- Следующий шаг: мультипроект Gradle (`settings.gradle.kts`, `libs.versions.toml`, `:app`, `core/*`, `feature/*`), `AppGraph`, базовый Compose-навигатор с экранами Чат/Ассистент/Share/Настройки, тема M3 light+dark, `core/a11y` с инвариантами, строки ru+en, `SecretStore`+`KeystoreAead`, redacting-логгер.

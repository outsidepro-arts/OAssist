# AGENTS.md — OAssist

Инструкции для агента, работающего над этим репозиторием. **Русский — приоритетный язык** обсуждений и документации.

## Что за проект

**OAssist** — Android-клиент LLM-агента, замена системного ассистента. Можно назначить помощником по умолчанию и вызывать assist-жестом. Opensource (MIT), без Google Play, в первую очередь для личного использования.

- **Пакет:** `ru.outsideproarts.oassist`
- **Язык:** чистый Kotlin (никакого Java, никаких React Native/Flutter)
- **Платформа:** Android, **minSdk 31**, compileSdk 36
- **UI:** Jetpack Compose + Material 3
- **Лицензия:** MIT

Полный план — в диалоге разработки; ключевые зафиксированные решения продублированы в `MEMORY.md`. Сверяйтесь с `MEMORY.md` перед началом работы.

## Стек и инструменты

| Инструмент | Выбор |
|---|---|
| Android SDK | `ANDROID_HOME` или `sdk.dir` в локальном `local.properties` |
| Gradle | Gradle Wrapper проекта |
| JDK | Совместимый JDK (минимум 17), `JAVA_HOME` или `java` из `PATH` |
| Kotlin | Версия из `gradle/libs.versions.toml`, запускается через Gradle |

Сборка — через Gradle Wrapper проекта (`gradlew.bat`). **Никогда** не используйте глобальные `gradle`/`mvn` без необходимости.

## Сборка и проверки

Gradle и Kotlin используют штатные демоны; Gradle выбирает число workers по доступным
процессорам. Параллельное выполнение проектов и build cache включены. Постоянно живущий
демон после завершения команды — штатное поведение, а не доказательство зависания.

```powershell
.\gradlew.bat :module:compileDebugKotlin
.\gradlew.bat :module:testDebugUnitTest
.\gradlew.bat :module:lintDebug
.\gradlew.bat :app:assembleDebug
```

Для небольших правок выбирайте затронутые модули. Изменения общей сборки проверяйте
через `:app:assembleDebug`, `testDebugUnitTest` и `lintDebug`. Агент может собирать APK.
На Unix используйте `./gradlew`.

- Запускайте Wrapper синхронно с потоковым выводом либо перенаправляйте вывод в лог,
  сохраняя настоящий код завершения. Выбирайте timeout инструмента по длительности
  задачи; отсутствие вывода само по себе не доказывает зависание.
- Скрипты `tools/run-build.ps1` и `tools/run-tests.ps1` — необязательные синхронные
  обёртки. Они наследуют окружение, не завершают демоны и возвращают код Gradle.
- Не завершайте все Java/Gradle/Kotlin-процессы перед сборкой. При проблеме сначала
  определите конкретный процесс и причину. `gradlew --stop` затрагивает демоны той же
  версии Gradle, поэтому перед ручной остановкой проверьте другие активные сборки.
- Ограничения workers/памяти и обходы особенностей файловой системы задавайте локально
  в `GRADLE_USER_HOME/gradle.properties`, а не в общих настройках репозитория.
- Не подгоняйте `GRADLE_OPTS` под JVM-аргументы сборки и не отключайте Gradle/Kotlin
  daemon ради особенностей агентского клиента. Диагностика OpenCode описана в
  `tools/README.md`.
- `clean`, `--offline` и `--no-build-cache` используйте для конкретной задачи,
  а не как обязательную подготовку или случайный способ лечения зависания клиента.
- Скрипты в `tools/` сохраняйте ASCII-only для совместимости с Windows PowerShell 5.1.

## Архитектура

Мультипроект Gradle, ручной DI (`AppGraph`) — **без** Hilt/Koin/аннотаций-процессоров, кроме Room (KSP). Ниже — целевой состав; фактически созданные модули на текущий milestone перечислены в `MEMORY.md`.

```
app/                    Application, MainActivity, AppGraph, манифест, VIS-стек
core/
  model/                доменные модели, Result, Time, redacting-логирование
  a11y/                 семантика/announcer/инварианты доступности (★ сквозное)
  data/                 Room, DataStore, SecretStore/KeystoreAead, репозитории
  net/                  Ktor, SSE-reader, DTO, retry
  provider/             ModelClient/AgentBackend: OpenAI-compat, Anthropic, Gemini, OpenCode
  agent/                AgentLoop, ToolRegistry, ContextBudget/Compactor, промпты
  tools/                встроенные инструменты (intent/ui/notify/memory/skill/shell/mcp)
  device/               AccessibilityBridge, IntentActions, ShizukuRoot/PrivExecutor
  mcp/                  MCP-клиент + MCP-сервер (Ktor localhost)
  speech/               SpeechOutput, Narrator, STT/TTS-движки, RecognitionService
feature/
  chat/                 сессии, лента, стрим, timeline действий
  assistant/            оверлей ассистента, AssistActivity, онбординг роли
  share/                ShareActivity
  settings/             провайдеры, промпты, скиллы, MCP, память, речь, привилегии, язык
```

Зависимости направлены: `feature/*` → `core/*` → `core/model`. **Никогда** не тяните `feature` в `core`.

## Жёсткие правила

### 1. Доступность (TalkBack) — обязательна, не опциональна

Каждый новый/изменённый UI-элемент обязан иметь:

- `contentDescription` **или** осмысленный текст; `Role`, `stateDescription` для состоящих/переключателей;
- `heading()` для заголовков секций, `paneTitle` для паней;
- touch target ≥ 48dp (используйте `Modifier.minTouchTarget()` из `core/a11y`);
- контраст AA (4.5:1 текст, 3:1 UI) в обеих темах;
- порядок фокуса = порядок чтения (`traversalIndex` где Compose спорит);
- у каждого **жеста** — альтернатива (кнопка/меню) **и** `CustomAccessibilityAction`;
- динамический контент — `LiveRegion.Polite`, ошибки — `Assertive`; транзиентные статусы — `announceForAccessibility`.

Запрещено: жест как единственный путь к действию; захардкоженные тексты в объявлениях; «просто иконка» без имени.

**Definition of Done каждого PR включает TalkBack-проход.**

### 2. Секреты — только через `SecretStore`

API-ключи, токены, пароли, значения `extraHeaders`, Basic Auth, Bearer MCP, ключи TTS/STT — **всё** через `core/data/secret/SecretStore` (Android Keystore AES-256-GCM). Никогда:

- не логировать секреты (используйте redacting-логгер из `core/model`);
- не отображать сохранённый секрет повторно — только маску `•••• (сохранён)` + действия «Заменить»/«Удалить»;
- не писать секреты в БД, экспорт без пароля, бэкап;
- не кэшировать plaintext дольше вызова.

`android:allowBackup="false"`, секреты — в DataStore с шифротекстом, ключ — в Android Keystore (без `setUserAuthenticationRequired`: ассистент должен работать с экрана блокировки).

### 3. Никаких захардкоженных списков моделей

Список моделей **только** через запрос к провайдеру (`/models`, `/v1/models`, `/v1beta/models`). Фолбэк — ручной ввод `modelId`. Никаких статических списков в коде/ресурсах (models.dev — только маршрутизация протокола агрегаторов, не список моделей).

### 4. Русский приоритетен

- `values/strings.xml` — **русский** (default-фолбэк), `values-en/strings.xml` — английский;
- дефолтные промпты — `assets/prompts/ru/*.md` (основа) + `assets/prompts/en/*.md`;
- язык STT/TTS по умолчанию `ru-RU`;
- все `contentDescription`/`stateDescription` — в обоих языках;
- новые строки — сразу в обе локали.

### 5. AccessibilityService — пассивный

Дерево интерфейса — **только по запросу** инструментом. Никаких потоков событий в модель. Сервис не издаёт accessibility-feedback (мирно соседствует с TalkBack).

### 6. Речь: два независимых контента

- **Ответ агента** — `SpeechOutputChannel`: `OFF | SYSTEM_TTS | ONLINE_TTS | SCREEN_READER`. Канал `SCREEN_READER` не издаёт звука: каждое завершённое предложение идёт очередью `TYPE_ANNOUNCEMENT` (`ScreenReaderSpeaker` → `View.announceForAccessibility`, читает сам TalkBack — аналог TeamTalk `TTSENGINE_QTANNOUNCEMENT`), видимый текст — **не** live region. Поведение зеркалит потоковую TTS.
- **Нарратив работы** (размышления, вызовы инструментов) — `Narrator`, отдельным тумблером, **только** через экранный чтец. Одновременно отображается визуально — сворачиваемый timeline с иконками статуса, навигируемый TalkBack.

### 7. Модель данных провайдеров/режимов

- `ProviderProfile` — **несколько** профилей (`OPENAI_COMPAT | ANTHROPIC | GEMINI | OPENAI_RESPONSES | OPENCODE`), каждый со `baseUrl`, `apiKeyRef` (alias в `SecretStore`), `extraHeaders`. `OPENCODE` — агрегатор (Zen/Go): протокол каждой модели выбирается по цепочке «ручной override → models.dev (`provider.npm`) → эвристика по id».
- `ModeConfig` — три режима (`CHAT | ASSISTANT | SHARE`), у каждого **свои** провайдер, модель и initial prompt.
- **Общее** на все режимы: скиллы, память (`MEMORY.md`), MCP-серверы, STT/TTS, привилегии, каталог инструментов.

### 8. Слои возможности тулов

`intent` (интенты/провайдеры) → `ui` (AccessibilityService) → `privileged` (Shizuku/root). Инструмент декларирует минимальный `PrivilegeLevel`, рантайм деградирует явно, никогда не молча.

## Соглашения по коду

- Kotlin only; корутины/Flow, никаких callback-адов.
- Именование: `PascalCase` классы, `camelCase` свойства/функции, `SCREAMING_SNAKE` константы.
- Суффиксы по слоям: `*Repository`, `*DataSource`, `*UseCase` (только если реально есть логика), `*ViewModel`, `*Screen`, `*Service`, `*Receiver`.
- Ошибки — `Result`/сейф-типы из `core/model`, не голые `throw` сквозь слои.
- Комментарии — **только** если без них не обойтись (неочевидные инварианты, хаки под OEM). Не комментировать очевидное.
- Никаких `TODO`/заглушек в коде без задачи в `MEMORY.md` → «Открытые вопросы».
- Ресурсы: строки в `strings.xml`, размеры в `dimens.xml`, никаких хардкодов в `dp`/`sp` кроме семантики.

## Пишем доступный Compose UI

Доступность — сквозное требование, не опция. Стандарты: WCAG 2.2 + CVS Health `android-compose-accessibility-techniques`. Визуал не меняется — доступность его сопровождает.

### Канонические компоненты

Использовать из `core/ui/components/Components.kt`. Новые экраны **не пишут** свои Switch/Slider/Dropdown — переиспользуют:

| Компонент | Назначение | А11y-семантика |
|---|---|---|
| `OAssistSwitchRow` | Switch-ряд | `toggleable(role=Role.Switch)` на Row, `Switch(onCheckedChange=null)`, `stateDescription` |
| `OAssistSettingRow` | Кнопка-строка настроек | `clickable(role=Role.Button)`, `oassistMergedContent()`, иконки `contentDescription=null` |
| `OAssistPanel` | Карточка с именем | `paneTitle` (или `showTitle=true` для визуального заголовка + `heading()`) |
| `OAssistSectionHeading` | Заголовок секции | `heading()` |
| `OAssistLiveStatus` | Transient-статус | `liveRegion=Polite/Assertive` |

Helpers из `core/a11y/Semantics.kt`: `oassistTouchTarget()`, `oassistHeading()`, `oassistPaneTitle()`, `oassistStateDescription()`, `oassistRole()`, `oassistCustomAction()`, `oassistSwitchRow()`, `oassistMergedContent()`, `oassistSlider()`, `oassistDropdownClassName()`, `oassistLiveRegion()`.

### Правила

1. **Touch target ≥ 48dp** — `oassistTouchTarget()` на всё интерактивное.
2. **Имя + роль + состояние** — каждый контрол имеет `contentDescription`/`label`, правильную `Role`, `stateDescription` если есть состояние.
3. **Жест ≠ единственный путь** — свайп/long-press имеют кнопочный аналог + `CustomAccessibilityAction` (`oassistCustomAction`).
4. **Live region — не на стриме** — видимый накапливающийся текст (ответ агента) НЕ live region. Ответ идёт через `ScreenReaderSpeaker` (`TYPE_ANNOUNCEMENT` с якоря-View); скрытый announcer-узел (`AnnouncerHost`/`FlowAnnouncer`) — только для нарратива и transient-статусов.
5. **Дропдауны** — `ExposedDropdownMenuBox` + `menuAnchor(PrimaryNotEditable)` + `oassistDropdownClassName()` (TalkBack: «Выпадающий список», не «Окно редактирования»).
6. **Слайдеры** — `oassistSlider()` (contentDescription + stateDescription + liveRegion + onKeyEvent D-PAD).
7. **Секреты** — только через `SecretStore`, значение не переотображается (маска `•••• (сохранён)` + Заменить/Удалить).
8. **Строки** — все в `values/strings.xml` (ru) и `values-en/strings.xml` (en), хардкод запрещён. Каждый `contentDescription`/`stateDescription` — через `stringResource`.
9. **Токены цвета** — только `MaterialTheme.colorScheme.*`, хардкод `Color(0x…)` запрещён.
10. **`Role.Button` не дублировать** — `Modifier.clickable(role=Role.Button)` и Material3-компоненты уже дают роль. Не вешать `oassistRole(Role.Button)` на `OAssistSettingRow`/`IconButton`/`TextButton`/`Button`.

### Анти-паттерны (не делать)

- `Modifier.oassistRole(Role.Switch)` на статическом `Text` → TalkBack читает «переключатель», фокус ловушит. Использовать `OAssistSwitchRow`.
- `Text(text = m.id)` с сырым API-идентификатором → TalkBack читает кракозябры. Обернуть в `TextButton` + `contentDescription = stringResource(...)`.
- `OAssistPanel(title = X) { Text(X) }` → дубль paneTitle+Text. Либо `showTitle=true` без paneTitle, либо только paneTitle.
- `Icon(contentDescription = "Иконка")` без действия → декоративная иконка = `contentDescription = null` + визуальный `Text(label)`.
- `Switch(onCheckedChange = ...)` без `OAssistSwitchRow` → Row+label читаются отдельно от Switch.
- `Slider` без `contentDescription` → TalkBack читает только значение, нет имени.

### Чеклист code review нового UI

1. Touch targets ≥ 48dp.
2. `contentDescription`/`label` на каждом контроле.
3. `Role` (Button/Switch/DropdownList).
4. `stateDescription` для состоящих.
5. `paneTitle`/`heading()` для секций/панелей.
6. `liveRegion` — не на стриме, Polite для статусов, Assertive для ошибок.
7. Строки ru+en.
8. Канон-компоненты (`OAssistSwitchRow`, `OAssistSettingRow`, `oassistSlider`, `oassistDropdownClassName`).
9. `CustomAccessibilityAction` для жестов.
10. TalkBack-проход (DoD).

## Тесты

- Юнит: парсеры SSE/провайдеров, `AgentLoop` (фикстурный `ModelClient`), `ContextCompactor`, JSON-схемы тулов, `ActionRisk`, `SecretStore` (round-trip + redacting).
- Instrumentation: `AccessibilityServiceTestHarness`, точность клика по индексу, `RecognitionService`, VIS-запуск.
- Ручной сценарий-чеклист по Milestone — в `MEMORY.md`.

## Чего не делать

- Не добавлять Java, Reflection-based DI, библиотеки-обёртки без нужды.
- Не тянуть тяжёлые зависимости «на будущее».
- Не писать секреты в git (`.env`, `local.properties`, ключи) — проверяйте `git status` перед коммитом.
- Не коммитить без явной просьбы.
- Git ведём на английском: сообщения коммитов (conventional commits), ветки, теги — только English.
- Не менять `applicationId`/`namespace` — `ru.outsideproarts.oassist` зафиксирован.

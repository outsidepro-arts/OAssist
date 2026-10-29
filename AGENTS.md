# AGENTS.md — OAssist

Инструкции для агента, работающего над этим репозиторием. **Русский — приоритетный язык** обсуждений и документации.

## Что за проект

**OAssist** — Android-клиент LLM-агента, замена системного ассистента. Можно назначить помощником по умолчанию и вызывать assist-жестом. Opensource (MIT), без Google Play, в первую очередь для личного использования.

- **Пакет:** `ru.outsideproarts.oassist`
- **Язык:** чистый Kotlin (никакого Java, никаких React Native/Flutter)
- **Платформа:** Android, **minSdk 31**, compileSdk 36
- **UI:** Jetpack Compose + Material 3
- **Лицензия:** MIT

## Как читать инструкции и исследовать проект

- `AGENTS.md` содержит действующие правила работы, `MEMORY.md` — актуальное состояние,
  решения пользователя, открытые вопросы и незавершённые проверки. Прочитайте их перед
  работой; если клиент уже предоставил актуальный текст в контексте, повторное чтение не нужно.
- Подробная история — в `docs/history/`. Она содержит отменённые решения и старые команды;
  это свидетельства прошлых состояний, а не текущие инструкции. Не загружайте весь архив,
  README и все документы при старте. Открывайте нужные записи через указатель ниже.
- Требования текущей задачи пользователя имеют приоритет. Код и тесты показывают
  реализацию и проверенные сценарии; расхождение с требованием отмечайте как пробел,
  а не исправляйте документацию под код без решения пользователя. Неясную политику
  продукта не меняйте под видом редактуры инструкций.

| Задача | Что читать дополнительно |
|---|---|
| Compose UI, жесты, фокус, доступность или озвучивание | Разделы доступности и речи ниже; соответствующий раздел `MEMORY.md`; при изменении компонентов — `core/ui/.../components/Components.kt` и `core/a11y/.../Semantics.kt` |
| STT, PTT, hands-free, жизненный цикл речи | Разделы речи и незавершённых проверок в `MEMORY.md`; историю распознавания по `docs/history/README.md` только для затронутого сценария |
| Провайдеры, исходящие DTO, SSE, MCP | Правила провайдеров и тестов ниже; актуальные ограничения в `MEMORY.md`; тематические записи архива для регрессии |
| Сборка, релиз или ошибка инструментального вызова | Раздел сборки ниже; нужный раздел `tools/README.md`. Диагностика OpenCode/Windows относится только к этому клиенту/окружению |
| Причина решения, старый milestone, регрессия | Сначала `docs/history/README.md`, затем только указанный диапазон или найденная запись |

### Доступные инструменты

- Рабочий процесс не требует Codex, MCP или конкретной оболочки. Определите фактическую
  среду: Windows, WSL/Linux или контейнер; используйте её команды и пути.
- Если Serena доступна и связана с **этой рабочей копией**, используйте её для символов
  и ссылок. Иначе используйте поиск IDE, `rg` или обычный поиск по файлам и узкие чтения.
  Git MCP применяйте, если он доступен для этого репозитория; иначе используйте локальный Git.
  Отсутствие MCP не требует установки сервера и не блокирует работу.
- Сначала найдите файл/символ, затем читайте нужный фрагмент и его зависимости.
  Ограничивайте область и объём поиска; не выводите целиком большие логи, JSON и архив.
  В архиве одна строка может содержать несколько тысяч символов: одного лимита строк мало.
  Уже прочитанный неизменившийся фрагмент не перечитывайте без причины.

### Сопровождение состояния

- Перед правками проверьте Git status; не перезаписывайте чужие изменения и не включайте
  их в свою задачу. Не делайте stash/reset/перенос файлов ради чистого дерева без необходимости.
- При изменении поведения обновите соответствующий пункт `MEMORY.md` и единый список
  открытых вопросов/проверок. Завершённые пункты заменяйте актуальным статусом, а не
  дописывайте ещё одну противоречащую сводку в конец.
- Подробности новой диагностики сохраняйте отдельной датированной записью в `docs/history/`
  и добавляйте ссылку в указатель. Не превращайте `MEMORY.md` снова в журнал.
  Сохраняйте действующие ограничения пользователя, осознанные отказы и проверки,
  которые ещё не выполнены; исторические результаты не означают проверку нового изменения.

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

Это примеры задач, а не обязательные четыре отдельных запуска. Выбирайте проверки по
изменению; совместимые задачи можно передать одному вызову Wrapper. Для правок только
документации проверяйте ссылки, примеры и согласованность; Android-сборка не требуется.
Для UI и затрагивающих его изменений TalkBack-проход остаётся частью DoD: отсутствие
устройства или экранного чтеца фиксируйте как непроведённую проверку, не как успех.

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

Мультипроект Gradle, ручной DI (`AppGraph`) — **без** Hilt/Koin/аннотаций-процессоров, кроме Room (KSP). Ниже — фактическое размещение подсистем; подключённые модули проверяйте по `settings.gradle.kts`, версии — по Wrapper и `gradle/libs.versions.toml`.

```
app/                    Application, MainActivity, AppGraph, манифест, VIS-стек
core/
  model/                доменные модели, Result, Time, redacting-логирование
  a11y/                 семантика/announcer/инварианты доступности (★ сквозное)
  ui/                   общие Compose-компоненты, тема, Markdown
  data/                 Room, DataStore, SecretStore/KeystoreAead, репозитории
  net/                  Ktor HttpClient, состояние сети, redacting HTTP-логов
  provider/             ModelClient, DTO/SSE/retry: OpenAI, Anthropic, Gemini, OpenCode
  agent/                AgentLoop, HistoryBudget, DeviceActions/UiActions, approval; mcp/ — клиент
  device/               PrivExecutor, ShizukuExecutor/UserService, RootExecutor
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
- touch target ≥ 48dp (используйте `Modifier.oassistTouchTarget()` из `core/a11y`);
- контраст AA (4.5:1 текст, 3:1 UI) в обеих темах;
- порядок фокуса = порядок чтения (`traversalIndex` где Compose спорит);
- у каждого **жеста** — альтернатива (кнопка/меню) **и** `CustomAccessibilityAction`;
- динамические статусы — `LiveRegion.Polite`, ошибки — `Assertive`; транзиентные
  сообщения без статусного узла объявляйте через существующий announcer
  (`announceForAccessibility`), без дублирования того же объявления двумя механизмами.
  Для накопительного текста ответа live region запрещён, озвучивание — по правилам речи ниже.

Запрещено: жест как единственный путь к действию; захардкоженные тексты в объявлениях; «просто иконка» без имени.

**DoD изменений UI, жестов, фокуса и озвучивания включает TalkBack-проход по затронутым сценариям.**

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

- `ProviderProfile` — **несколько** профилей. `preset` — готовый рецепт известного
  провайдера (`OPENAI | GOOGLE | ANTHROPIC | OPENCODE`: Base URL, протокол, каталог
  и авторизация под капотом; у пресетов есть свои выборы — Google: версия API
  v1beta/v1, OpenCode: эндпоинт Zen / Zen Go) либо `CUSTOM` («Своя настройка») с
  открытыми полями. Новый нестандартный провайдер — новый рецепт поверх общих
  механизмов (роутинг протокола, каталог, auth-схемы), а не новый wire-протокол.
  Поля профиля: `baseUrl` (корень API вместе с версией, как в документации;
  все пути дописываются как есть), `apiKeyRef` (alias в `SecretStore`),
  `extraHeaders`, `protocol` (фикс протокола либо null — per-model резолв
  «ручной override → models.dev (`provider.npm`) → эвристика по id» с фолбэком),
  `fetchScheme` (`MODELS` = `{base}/[catalogPath]` с lenient-парсером формата
  либо `NONE` = ручной ввод), `catalogPath`, `extraBody` (JSON-объект: ключи
  мержатся поверх тел запросов к модели) и `authScheme`
  (`NATIVE | BEARER | API_KEY_HEADER | BASIC | NONE`; нативная — всегда
  key-based: Bearer/x-api-key/x-goog-api-key). Учётные данные и имя при смене
  пресета не затираются; переход в `CUSTOM` показывает спрятанные значения.
- `ModeConfig` — три режима (`CHAT | ASSISTANT | SHARE`), у каждого **свои** провайдер, модель и initial prompt.
- **Общее** на все режимы: скиллы, память пользователя в приложении (`MemoryStore`), MCP-серверы, STT/TTS, привилегии, каталог инструментов. Файл `MEMORY.md` в корне репозитория — отдельная память разработки, его нельзя подставлять в промпты приложения.

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

### Миграции данных

- Совместимость и миграции — только для форматов, которые реально дошли до
  пользователей (отгружены в сборках для тестирования/использования).
  Правки текущей сессии, ещё не уходившие в сборку, миграциями не
  сопровождаются: их промежуточный формат можно переписать напрямую.
- Миграция планируется в рамках основной задачи и делается одним шагом:
  из отгружённого формата сразу в текущий, без цепочек
  «промежуточный формат → следующий».
- Перед написанием миграции проверьте, попадал ли формат в сборку; если
  сомневаетесь — спросите пользователя, а не планируйте совместимость
  «на всякий случай».

## Пишем доступный Compose UI

Доступность — сквозное требование. Стандарты: WCAG 2.2 + CVS Health `android-compose-accessibility-techniques`. При задаче только на доступность сохраняйте визуальный дизайн, если пользователь не запросил его изменение.

### Канонические компоненты

Компоненты находятся в `core/ui/.../components/Components.kt`. Для переключателей
используйте `OAssistSwitchRow`; для Slider и Dropdown — Material-компоненты с рецептами
и helpers ниже. Не создавайте дубли существующих компонентов.

| Компонент | Назначение | А11y-семантика |
|---|---|---|
| `OAssistSwitchRow` | Switch-ряд | `toggleable(role=Role.Switch)` на Row, `Switch(onCheckedChange=null)`, `stateDescription` |
| `OAssistSettingRow` | Кнопка-строка настроек | `clickable(role=Role.Button)`, `oassistMergedContent()`, иконки `contentDescription=null` |
| `OAssistPanel` | Карточка с именем | `paneTitle` (или `showTitle=true` для визуального заголовка + `heading()`) |
| `OAssistSectionHeading` | Заголовок секции | `heading()` |
| `OAssistLiveStatus` | Transient-статус | `liveRegion=Polite/Assertive` |

Helpers из `core/a11y/.../Semantics.kt`: `oassistTouchTarget()`, `oassistHeading()`, `oassistPaneTitle()`, `oassistStateDescription()`, `oassistRole()`, `oassistCustomAction()`, `oassistSwitchRow()`, `oassistMergedContent()`, `oassistSlider()`, `oassistDropdownClassName()`, `oassistLiveRegion()`.

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
- Пункт **выбора модели** в виде `Text(text = m.id)` не даёт доступного действия и понятного имени. Используйте `TextButton` с `contentDescription = stringResource(...)`. Статическому идентификатору вне выбора задайте понятную подпись; превращать его в кнопку не нужно.
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

- Юнит: парсеры SSE/провайдеров, `AgentLoop` (фикстурный `ModelClient`),
  `HistoryBudget`/`ImageBudget`, JSON-схемы тулов, `ActionRisk`, `SecretStore`
  (round-trip + redacting).
- Instrumentation/устройство: точность клика по индексу, `RecognitionService`, VIS,
  Compose-жесты/семантика и жизненный цикл. Существующие instrumentation-тесты —
  в `app/src/androidTest`; отдельный `AccessibilityServiceTestHarness` пока не реализован.
- При изменении исходящих DTO или настроек `Json` проверяйте фактически сериализованное
  тело запроса: обязательные поля со значениями по умолчанию, вложенные объекты и null.
  Тесты обработки ответов MockEngine не заменяют проверку исходящего запроса.
- При переносе состояния в ViewModel оставляйте Compose-анимации в composition scope
  (`rememberCoroutineScope`/`LaunchedEffect`): обычный ViewModel scope не даёт `MonotonicFrameClock`.
- Актуальные незавершённые проверки — в `MEMORY.md`; старые сценарии milestone —
  через `docs/history/README.md`, только для затронутой подсистемы. Проверяйте результат
  текущей команды и относящийся к ней отчёт; старый зелёный лог не подтверждает новую правку.

## Чего не делать

- Не добавлять Java, Reflection-based DI, библиотеки-обёртки без нужды.
- Не тянуть тяжёлые зависимости «на будущее».
- Не писать секреты в git (`.env`, `local.properties`, ключи) — проверяйте `git status` перед коммитом.
- Не коммитить без явной просьбы.
- Git ведём на английском: сообщения коммитов (conventional commits), ветки, теги — только English.
- Не менять `applicationId`/`namespace` — `ru.outsideproarts.oassist` зафиксирован.

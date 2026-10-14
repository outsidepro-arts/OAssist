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

| Инструмент | Путь / версия |
|---|---|
| Android SDK | `c:\dev\android-sdk` |
| Gradle | `c:\dev\gradle\gradle-9.4.1` |
| JDK | `c:\dev\jdk\jdk-21.0.10+7` (проверено 2026-10-02: `bin\java.exe` именно там; сам `c:\dev\jdk` — только контейнер) |
| Kotlin | `c:\dev\kotlin\kotlinc` |
| Все тулчейны | `c:\dev\*` |

Сборка — через Gradle Wrapper проекта (`gradlew.bat`). **Никогда** не используйте глобальные `gradle`/`mvn` без необходимости.

## Стратегия проверок: точечные билды, а не APK

`assembleDebug` собирает APK целиком (30–90 с, плодит Kotlin compile daemon). **Контрольный (финальный) `assembleDebug` выполняет только пользователь вручную — агент не запускает его никогда** (ни синхронно, ни фоном, ни через `tools/run-build.ps1`), включая верификацию Milestone и сборку артефакта. Гейты агента: точечные таски + юнит-тесты + линт. Для итераций над кодом используем **точечные** таски, которые проверяют корректность кода без APK:

```bat
gradlew.bat :module:compileDebugKotlin          : REM компиляция Kotlin (включая KSP) одного модуля
gradlew.bat :module:compileDebugUnitTestKotlin  : REM то же + testSources
gradlew.bat :module:testDebugUnitTest --no-daemon           : REM юнит-тесты модуля (JVM), ТОЛЬКО с --no-daemon
gradlew.bat :app:compileDebugKotlin             : REM компиляция всего графа (без APK)
gradlew.bat :module:lintDebug                   : REM Android Lint (поверх скомпилированного)
```

**Юнит-тесты — только с `--no-daemon`.** Без него прогоны виснут/убиваются harness'ом (`ChildProcess.kill`, прецедент 2026-10-04: `testDebugUnitTest` без флага умирал, с флагом — зелёный). Запрет импровизации `--offline`/`--no-build-cache` остаётся в силе; `--no-daemon` — не импровизация, а обязательный флаг для тестов (и допустим для `assembleDebug`).

Типичный цикл правки: **правка → `:module:compileDebugKotlin` + `:module:testDebugUnitTest --no-daemon` (5–15 с) + `:module:lintDebug` → контрольный `:app:assembleDebug` выполняет пользователь вручную (агент не запускает)**.

**Защита от зомби-демонов.** Каждый запуск `compileDebugKotlin` поднимает Kotlin compile daemon (живёт 2 ч). При «прерванных» билдах daemon остаётся висеть → следующие билды стартуют медленнее или зависают на старте. Перед большим билдом или если предыдущий был прерван:

```bat
gradlew.bat --stop                              : REM остановить Gradle daemon
for /f "tokens=2 delims=," %i in ('tasklist /fi "imagename eq java.exe" /fo csv /nh ^| findstr KotlinCompileDaemon') do taskkill /pid %i /f
```

Ключевой признак зомби: `tasklist | findstr java` показывает несколько `java.exe` с `-Xmx4g` и `KotlinCompileDaemon` в командной строке. Достаточно оставить один процесс — Gradle daemon (`gradle --status` → `IDLE`).

**Дисциплина длинных команд (урок инцидента 2026-10-02).** Инструментальный вызов, который молчит дольше пары минут, неотличим от зависания — ни для пользователя, ни для агента. Правила:

- **Никакого `| Select-Object -Last` на длинных вызовах.** Пайп буферизует весь вывод до завершения процесса. Длинный Gradle-запуск — только фоном с перенаправлением в файл и опросом:
  ```powershell
  $env:JAVA_HOME='C:\dev\jdk\jdk-21.0.10+7'; $env:ANDROID_HOME='c:\dev\android-sdk'; $env:ANDROID_SDK_ROOT='c:\dev\android-sdk'
  $p = Start-Process -FilePath .\gradlew.bat -ArgumentList ':app:assembleDebug','--console=plain' -RedirectStandardOutput C:\Users\denis\AppData\Local\Temp\opencode\build-log.txt -RedirectStandardError C:\Users\denis\AppData\Local\Temp\opencode\build-err.txt -NoNewWindow -PassThru
  ```
  затем опросами `Get-Content <лог> -Tail 30` и проверкой живости `Get-Process -Id $p.Id`. Один инструментальный вызов — одна быстрая операция (старт или опрос), никаких многоминутных блокировок.
- **Разграничение «агент vs человек» (урок 2026-10-04).** Фон+poll через `Start-Process` — только для ручных запусков из живой консоли: harness убивает фоновые обёртки внутри инструментального вызова (`ChildProcess.kill`, проверено — демон при этом выживает и добегает). Агентские прогоны — всегда синхронно, с явным таймаутом под задачу (`compile`/`test` модуля — от 120 с, `lint` — от 300 с; `assembleDebug` агенту запрещён — контрольную сборку делает пользователь).
- **Никаких импровизированных флагов в агентских прогонах (урок 2026-10-04).** `--offline` и `--no-build-cache` в руках агента запрещены: они форсируют полное перевыполнение самой тяжёлой фазы (`processDebugManifestForPackage` → ресурсы → dex → package), раздувают вывод и вешают синхронный вызов вместе со всей сессией (прецедент: `assembleDebug --offline --no-build-cache` встал на `processDebugManifestForPackage`, сессия остекленела целиком, демон при этом остался здоров — все 11 билдов `Received`/`Finishing` сбалансированы, APK 72 МБ собрался). Полный `assembleDebug` — только вручную пользователем (контрольная сборка); агенту запрещён и этот запуск.
- **Никакого `clean` для диагностики.** На Windows `clean` хрупок: `Unable to delete ... classes.jar` — файл держат индексатор IDE / Defender / параллельный Gradle (прецедент 2026-10-02: `clean` упал за 2 с на `:core:model:clean`, оставив проект в полуснесённом состоянии). Остатки удаляются точечно, по одному файлу/каталогу, и только после `gradlew --stop` при закрытой Android Studio.
- **Переменные окружения — только через `$env:` в PowerShell.** Конструкция `cmd /c "set A=... && ..."` не работает (в PowerShell 5.1 нет `&&`); путь JDK содержит `+`, что дополнительно ломает bat-проверки `if exist` при передаче через `cmd /c`.
- **Перед разрушительными операциями — проверить чужую активность:** `gradlew --status` + `tasklist /fi "imagename eq java.exe"`. Параллельный актор (IDE-синк, вторая сессия) + файловые локи = рецидив 2026-10-02 (чужие билды в логе демона за минуты до падения).
- **Диагностика «висит ли билд» — по логу демона, а не по молчанию инструмента:** `C:\Users\<user>\.gradle\daemon\<gradle-ver>\daemon-<pid>.out.log`. Маркеры: `Received command: Build` (старт), `BUILD FAILED/SUCCESSFUL` (итог), `Finishing executing command` (результат отдан клиенту — дальше Gradle ни при чём, смотреть клиент/пайп/harness), `Stop ... not running a build` (демона остановил чужой `--stop`).

## Сборка и тесты (Windows, минимизация JVM)

- Синхронно разрешены только быстрые задачи: `:<module>:compileDebugKotlin`,
  `:<module>:testDebugUnitTest`, таймаут ≤ 300000 мс.
- Тяжёлые задачи (`assemble*`, `lint*`, полные прогоны) — **только** через
  `tools/run-build.ps1 start|status|stop` (detached + polling). Никогда не запускать
  их одним синхронным tool-call'ом.
- Перед любой сборкой — `tools/run-build.ps1 clean-orphans` (убивает Gradle/Kotlin daemon'ы,
  оставшиеся от предыдущих запусков, через `taskkill /T /F`).
- `org.gradle.jvmargs` и `GRADLE_OPTS` (в `tools/run-*.ps1`) обязаны совпадать
  **в точности** — только так сборка идёт в клиентском JVM без single-use daemon.
  В составе строки обязательны `-Xms64m` (gradlew.bat добавляет
  `DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"`) и `--add-modules=ALL-DEFAULT`
  (java.exe этого JDK инжектирует флаг в каждый запуск). Меняете одно —
  меняйте оба, иначе вернётся форк single-use daemon и сироты при kill.
- `org.gradle.internal.instrumentation.agent=false` — обязателен (клиент gradlew
  запущен без `-javaagent`, иначе mismatch agent-статуса форкает single-use daemon).
- `kotlin.compiler.execution.strategy=in-process` — обязателен.
- Скрипты в `tools/` пишем ASCII-only: Windows PowerShell 5.1 читает `.ps1`
  без BOM как ANSI, em-dash/кириллица ломают парсер.

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

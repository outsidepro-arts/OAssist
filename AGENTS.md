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
| JDK | `c:\dev\jdk` (проверить раскладку перед использованием) |
| Kotlin | `c:\dev\kotlin\kotlinc` |
| Все тулчейны | `c:\dev\*` |

Сборка — через Gradle Wrapper проекта (`gradlew.bat`). **Никогда** не используйте глобальные `gradle`/`mvn` без необходимости.

Полезные команды:

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
gradlew.bat lint
gradlew.bat :app:compileDebugKotlin
```

После каждого непустого изменения — **прогонять** `gradlew.bat assembleDebug` (и `lint`/`test` там, где применимо). Не считать задачу готовой, пока сборка не зелёная.

## Архитектура

Мультипроект Gradle, ручной DI (`AppGraph`) — **без** Hilt/Koin/аннотаций-процессоров, кроме Room (KSP).

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

Список моделей **только** через запрос к провайдеру (`/models`, `/v1/models`, `/v1beta/models`, у OpenCode — `/provider`). Фолбэк — ручной ввод `modelId`. Никаких статических списков в коде/ресурсах.

### 4. Русский приоритетен

- `values/strings.xml` — **русский** (default-фолбэк), `values-en/strings.xml` — английский;
- дефолтные промпты — `assets/prompts/ru/*.md` (основа) + `assets/prompts/en/*.md`;
- язык STT/TTS по умолчанию `ru-RU`;
- все `contentDescription`/`stateDescription` — в обоих языках;
- новые строки — сразу в обе локали.

### 5. AccessibilityService — пассивный

Дерево интерфейса — **только по запросу** инструментом. Никаких потоков событий в модель. Сервис не издаёт accessibility-feedback (мирно соседствует с TalkBack).

### 6. Речь: два независимых контента

- **Ответ агента** — `SpeechOutputChannel`: `OFF | SYSTEM_TTS | ONLINE_TTS | SCREEN_READER`. Канал `SCREEN_READER` не издаёт звука: каждое завершённое предложение публикуется в скрытый announcer-узел (`LiveRegion.Polite`), видимый текст — **не** live region. Поведение зеркалит потоковую TTS.
- **Нарратив работы** (размышления, вызовы инструментов) — `Narrator`, отдельным тумблером, **только** через экранный чтец. Одновременно отображается визуально — сворачиваемый timeline с иконками статуса, навигируемый TalkBack.

### 7. Модель данных провайдеров/режимов

- `ProviderProfile` — **несколько** профилей (`OPENAI_COMPAT | ANTHROPIC | GEMINI | OPENCODE`), каждый со `baseUrl`, `apiKeyRef` (alias в `SecretStore`), `extraHeaders`.
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

## Тесты

- Юнит: парсеры SSE/провайдеров, `AgentLoop` (фикстурный `ModelClient`), `ContextCompactor`, JSON-схемы тулов, `ActionRisk`, `SecretStore` (round-trip + redacting).
- Instrumentation: `AccessibilityServiceTestHarness`, точность клика по индексу, `RecognitionService`, VIS-запуск.
- Ручной сценарий-чеклист по Milestone — в `MEMORY.md`.

## Чего не делать

- Не добавлять Java, Reflection-based DI, библиотеки-обёртки без нужды.
- Не тянуть тяжёлые зависимости «на будущее».
- Не писать секреты в git (`.env`, `local.properties`, ключи) — проверяйте `git status` перед коммитом.
- Не коммитить без явной просьбы.
- Не менять `applicationId`/`namespace` — `ru.outsideproarts.oassist` зафиксирован.

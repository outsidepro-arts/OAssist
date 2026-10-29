# карта исходного кода

Файл предназначен для быстрой ориентации по коду.
Исходный код определяет фактическую реализацию. Ожидаемое поведение определяют согласованные требования и контракты.

## 1. Быстрая маршрутизация задач

| Задача | Куда идти |
|---|---|
| UI чата, сообщения, поле ввода, история диалога | `feature/chat/` |
| Системный ассистент, оверлей, Assist UI | `feature/assistant/`, `app/vis/` |
| Экраны настроек | `feature/settings/` |
| Общие UI-компоненты, тема, Markdown | `core/ui/` |
| Доступность, TalkBack, семантика | `core/a11y/` |
| Модель провайдеров, конфигурация режимов | `core/model/Providers.kt` |
| Редактирование провайдера в UI | `feature/settings/ProviderEditScreen.kt` |
| Хранение профилей провайдеров | `core/data/provider/` |
| API LLM, HTTP-запросы, SSE, DTO | `core/provider/` |
| Выбор протокола и маршрутизация моделей | `core/provider/RoutingModelClient.kt`, `core/provider/opencode/`, `core/provider/modelsdev/` |
| Агентный цикл, инструменты, подтверждения | `core/agent/` |
| MCP-клиент и инструменты | `core/agent/mcp/` |
| Настройки MCP-серверов | `core/data/mcp/`, `feature/settings/McpServersScreen.kt` |
| STT, TTS, микрофон, аудиомаршрутизация | `core/speech/` |
| Чаты, Room, DataStore, секреты | `core/data/` |
| Root, Shizuku, привилегированные операции | `core/device/` |
| Сеть, Ktor, состояние подключения | `core/net/` |
| Сборка зависимостей приложения (DI) | `app/di/AppGraph.kt` |
| Диагностика, экспорт, контракты логирования | `core/model/diagnostics/`, `core/data/diagnostics/`, `diagnostic-lint/` |
| Gradle, зависимости и сборка | `settings.gradle.kts`, `gradle/`, `tools/` |

## 2. Структура Gradle-модулей

```text
OAssist/
├── app/                   Точка входа, Android-сервисы, DI
├── core/
│   ├── model/             Доменные модели и контракты
│   ├── agent/             Агентный цикл, инструменты, MCP
│   ├── provider/          LLM API, DTO, SSE, маршрутизация
│   ├── data/              Хранилища и репозитории
│   ├── net/               HTTP и состояние сети
│   ├── speech/            STT, TTS, аудио
│   ├── a11y/              Доступность и экранные чтецы
│   ├── ui/                Общие Compose-компоненты
│   └── device/            Root и Shizuku
├── feature/
│   ├── chat/              Интерфейс и сессии чата
│   ├── assistant/         Системный ассистент и оверлей
│   ├── settings/          Экраны настроек
│   └── share/             Android Share
└── diagnostic-lint/       Специализированные Lint-проверки
```

**Соглашение о путях:** короткий путь `core/agent/AgentLoop.kt` в этой карте обозначает файл:

`core/agent/src/main/kotlin/ru/outsideproarts/oassist/core/agent/AgentLoop.kt`

Аналогично для остальных Kotlin-модулей. Сначала выбирай модуль, затем указанный файл или подпакет.

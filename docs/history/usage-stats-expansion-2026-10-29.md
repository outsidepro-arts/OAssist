# Расширенная статистика использования, 2026-10-29

Задача пользователя: «статистика провайдера OpenCode довольно обширная, в ней есть и
стоимость запросов, и сколько осталось до ограничения» — привести статистику чата
в соответствие с фактическими форматами статистики поддерживаемых провайдеров и дать
максимально полную картину использования агента.

## Что показало исследование форматов (источники)

- **OpenCode (Console Inference API, <https://opencode.ai/v2/docs/console/api/inference#cost>)**:
  каждый ответ (и событие с финальным usage в стриме: usage-чанк `include_usage`,
  `response.completed`, `message_delta`, последний чанк Gemini) несёт расширение рядом
  с usage (для Gemini — в `usageMetadata`):

  ```json
  "usage": {
    "prompt_tokens": 1200, "completion_tokens": 300,
    "cost": 0.00412,
    "opencode": {
      "billing_source": "free|balance|go|go-plus|byok",
      "list_cost": 0.00412, "estimated": true, "allowance": 0.0031,
      "tokens": { "input": 1200, "output": 300, "reasoning": 120,
                  "cache_read": 8000, "cache_write_5m": 0, "cache_write_1h": 0 }
    }
  }
  ```

  `cost` — списано с баланса (0 для free и Go в лимите; для BYOK — оценка, `estimated`),
  `list_cost` — по прайсу модели, `allowance` — сколько запрос зачтён в лимит Go,
  `opencode.tokens.input` — без чтения/записи кэша, `output` — включая `reasoning`.
- **Остаток до ограничения у OpenCode**: per-request отдаётся только `allowance`;
  недокументированный `GET /zen/go/v1/usage` (плановые окна 5ч/неделя/месяц с
  `percent`/`status`/`resetsAt`) опрашивать **не стали** (решение пользователя:
  только данные ответов). Реально остатки берутся из HTTP-заголовков ответа.
- **OpenAI Completions**: `prompt_tokens_details.cached_tokens`,
  `completion_tokens_details.reasoning_tokens`; **Responses**:
  `input_tokens_details.{cached_tokens,cache_write_tokens}`,
  `output_tokens_details.reasoning_tokens`.
- **Anthropic Messages**: `input_tokens`, `output_tokens`,
  `cache_creation_input_tokens` (сумма `cache_creation.{ephemeral_5m,ephemeral_1h}_input_tokens`),
  `cache_read_input_tokens`; полный вход = input + запись + чтение кэша.
  Лимиты — заголовки `anthropic-ratelimit-{requests,input-tokens,output-tokens,tokens}-*`.
- **Gemini `usageMetadata`**: `thoughtsTokenCount` (биллится как output),
  `toolUsePromptTokenCount` (входит в `totalTokenCount`, но не в prompt/candidates),
  `cachedContentTokenCount`. `usageMetadata` **кумулятивен** и приходит на каждом
  чанке стрима.

## Что изменено

- `core/model/Usage.kt`: `ProviderUsage` дополнен полями `reasoningTokens`,
  `cacheWriteTokens`, `toolTokens`, `requests` (число round-trip'ов к модели за ход),
  `listCostUsd`, `allowanceUsd`, `billingSource` (`BillingSource`), `costEstimated`,
  `rateLimits` (`RateLimitSnapshot` с bucket'ами limit/remaining/resetAtEpochSeconds).
  `mergeUsage` складывает токены/цены и `requests`, берёт последний `rateLimits` и
  последний `billingSource`/`costEstimated`. `UsageTotals`/`UsageTotalsState` суммируют
  новое; ход без явных `requests` считается одним запросом.
- `core/provider`:
  - `opencode/OpenCodeUsage.kt` — DTO расширения OpenCode; `UsageMapping.kt` —
    общий маппер (нативные поля — источник истины, пробелы добираются из
    `opencode.tokens`).
  - `RateLimitHeaders.kt` — парсер `x-ratelimit-*` (OpenAI/generic, мульти-политика
    по первому значению) и `anthropic-ratelimit-*`; сброс lenient: RFC3339 → epoch,
    unix-секунды либо delay-seconds (IETF draft), длительность `6m0s` (OpenAI).
    `ProviderUsage.withRateLimits` приклеивает срез к финальному usage (usage без
    токенов, но с лимитами — не «пустой»).
  - Все четыре клиента читают `cost`/`opencode` (Completions — плюс легаси top-level
    `cost`; Responses — в `response.completed`/`response.incomplete`; Anthropic — в
    usage `message_delta`; Gemini — в `usageMetadata`) и прикладывают `rateLimits`
    из HTTP-заголовков ответа стрима.
  - Completions/Responses/Gemini парсят reasoning-токены, Responses —
    `cache_write_tokens`, Gemini — `toolUsePromptTokenCount`.
  - **Anthropic**: `cache_creation_input_tokens` больше не теряется (→
    `cacheWriteTokens`), `anthropicUsageOrNull(start, delta)` перекрывает поля
    message_start полями message_delta по одному полю (у агрегаторов delta несёт
    полную картину — суммирование задвоило бы input/cost); total восстанавливается
    с учётом кэша: input + запись + чтение + output.
  - **Gemini**: кумулятивный `usageMetadata` теперь берётся последним снимком, а не
    складывается по чанкам (суммирование задваивало счётчики — фактическая регрессия
    прежней логики).
- `core/agent/AgentLoop.kt`: итог хода получает `requests = rounds` (число
  round-trip'ов к модели, включая тул-раунды); `ChatTitler`-usage учитывается как
  один запрос в `UsageTotals`.
- UI (`core/ui/UsageLine.kt` + строки ru/en): строка хода — компактно (мышление,
  кэш-чтение/запись, стоимость); `OAssistUsageDetails` («Информация о чате») —
  полный разбив (вход/из них кэш-чтение, выход/из них мышление, запись в кэш,
  токены инструментов, всего, запросов к модели, ходов), блок «Стоимость»
  (списано [+оценка], по прайсу, зачтено в лимит, источник) и блок «Лимиты»
  (bucket'ы из заголовков: осталось из лимита, момент сброса); заголовки блоков —
  `OAssistSectionHeading` (heading для TalkBack). Totals-строка — с числом запросов.

## Совместимость

- Поля `ProviderUsage` аддитивные (nullable со значениями по умолчанию):
  старые payload в Room (`turns.payload`) читаются без миграции, `TurnCodec`
  (`ignoreUnknownKeys`) переживает и обратное направление. Миграция не нужна
  (проверено тестами `TurnCodecTest`).
- Легаси top-level `cost` в ответах Completions сохранён как фолбэк.

## Проверки

- JVM: model 50, provider 209, data 64, agent 414, ui 18, net 16, speech 113,
  a11y 4, device 13, feature:chat 5, feature:assistant 10 — без ошибок и пропусков
  (`testDebugUnitTest` по всему проекту). Новое: `OpenCodeUsageExtensionTest`
  (расширение по 4 протоколам, delta-wins Anthropic, tool/reasoning-токены),
  `RateLimitHeadersTest` (оба семейства заголовков, lenient-сброс, мусор),
  `StreamUsageTest` (кумулятивный usageMetadata Gemini — последний снимок;
  лимиты из заголовков доходят до Finish; delta-wins Anthropic в реальном стриме);
  расширены `UsageTest`, `TurnCodecTest`, `AgentLoopUsageTest`.
  Для stream-тестов MockEngine обёрнут подклассом, добавляющим `SSECapability`
  и вызов `ResponseAdapter` (как это делает OkHttp) — без этого Ktor SSE не работает.
- `:app:assembleDebug`, `lintDebug` core:model/provider/data/agent/ui и app — чисто.

## Что не проверено

- Фактические ответы живого OpenCode Zen/Go (`/zen/v1`, `/zen/go/v1`): формат
  `usage.opencode` взят из документации Console Inference API; поле `cost` top-level
  мы наблюдали ранее (комментарий `ProviderJson`), но блок `opencode` и заголовки
  лимитов на реальных запросах не сверялись. Парсер lenient: отсутствующее поле —
  просто пустая строка статистики.
- Rate-limit заголовки реальных провайдеров (OpenAI/Anthropic/агрегаторы) на живых
  запросах; у Gemini/части прокси заголовков может не быть — секция «Лимиты» скрыта.
- TalkBack-проход «Информации о чате» (новые статичные строки + два heading).
- Опрос `GET /zen/go/v1/usage` (остаток окон Go) осознанно отложен: endpoint
  недокументирован, решение пользователя — только данные ответов.

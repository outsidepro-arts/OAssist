# Очередь исправлений по аудиту 4 ноября 2026

Основание — [журнал аудита](audit-2026-11-04.md): 72 подтверждённые карточки, кандидатов нет. Это последовательная очередь из 58 основных задач и отдельного подтверждённого подшага PW-005 после PW-002; каждый подтверждённый ID входит ровно в одну задачу или этот подшаг. Числа — порядок, не новые идентификаторы. Группировка обозначает общий изменяемый контракт. UI-011 включён в UI-002. PW-005 подтверждён дополнительной проверкой первичного источника Google и кода после PW-002.

До начала снять актуальный baseline сборки и тестов затронутого дерева, установить текущий HEAD и чистоту, исключить прежние `.serena/` и `temp/`. Каждая задача начинается только после принятого локального атомарного commit предыдущей: свежий worker получает её IDs, требования, текущую базу и нужные файлы; сверяет карточку с HEAD и сначала по возможности воспроизводит сценарий. Если более раннее исправление уже устранило причину, закрывать ID только после проверки требования и независимого review с фиксацией результата; не создавать искусственную правку. Переоценки ниже обязательны на новом состоянии.

Для каждой задачи: подходящие регрессионные и интеграционные проверки стыков, `compileDebugKotlin`, `testDebugUnitTest`, релевантный `lintDebug` затронутых модулей; для межмодульных изменений дополнительно `:app:assembleDebug`. Независимый reviewer получает постановку и diff от базы задачи; после замечаний повторить затронутые проверки, проверить staged diff и создать атомарный commit. Затем следующий worker. Отсутствующее устройство, TalkBack или live API не заменяется моками и не считается PASS; при недоступности сохранять явный открытый gate. Никаких новых зависимостей, архитектур или миграций без соответствующего согласования и установления статуса выпуска формата.

Перед затронутой задачей подготовить конкретное решение, не блокируя независимые предшествующие шаги: DS-002 — сохранять более строгие локальные решения либо явно подтвердить повышение; SP-002 — ручное возобновление SCREEN_READER; UI-014 — фиксировать режим до конца активной сессии. Если выбор неочевиден, координатор рассматривает его с новым экспертом на этапе 5, не ожидая отсутствующего пользователя. UI-003 допускает явный отказ неподдерживаемому формату вместо реализации PDF. AD-010 требует определить смысл timeout и late reply; DS-003 — допустимое восстановление частичного импорта; PW-003 — безопасное поведение для неизвестных возможностей. Не считать выбор реализации заранее утверждённым.

## 1. AD-005 + AD-006 — Граница привилегированного shell

`core/device/PrivExecutor`, `PrivUserService`, `RootExecutor`, `core/agent/ShellTools`: общая безопасная грамматика argv и идентичность executable на границе сервиса. Проверить меняющие wm/dumpsys аргументы, пользовательские пути и допустимые системные команды; подтверждение PRIVILEGED.WRITE сохраняется.

## 2. AD-001 + AD-003 — Вывод процесса и передача timeout

`core/device/RootExecutor`, `PrivUserService`: дренирование stdout с ограничением сохраняемого объёма и передача срока из Parcel. Проверить вывод больше pipe, short/long timeout, отмену и освобождение ресурсов; root/Shizuku интеграция на устройстве.

## 3. AD-002 — Проверка exitCode системных команд

После AD-001/003, `core/device` и вызовы settings/input: учитывать ненулевой код до преобразования результата. Проверить fake process с ненулевым кодом, get/put и input; root integration отдельно.

## 4. AD-010 — Контракт клиентского срока Binder

После AD-001/003 и AD-002 переоценить `core/device/PrivServiceClient`: установить смысл timeout и поведение late reply. Проверить управляемый Binder с ответом после срока; отделять отмену ожидания от остановки удалённой команды. Новая IPC-архитектура не предписана.

## 5. AB-001 — Восстановление accessibility при отмене

После AD-002/010, `app/di/AppGraph` и `core/agent/UiActions`: защита восстановления внешней настройки после отключения. Проверить отмену на delay, success и ошибку restore, сохранность более нового состояния и устройство.

## 6. AD-008 — Не повторять жест с неизвестным исходом

После AD-010 и AB-001, `core/agent/UiActions`: различать отклонённый и неопределённый исход. Проверить поздний callback и отсутствие второго swipe; устройство с TalkBack on/off и разными привилегиями.

## 7. AD-009 — Координаты снимка окна

`core/agent/UiActions`: различать window и display shot. Проверить сетку и ненулевой origin на API 33/34+, затем диалог и split-screen на устройстве; позднее переоценить UI-010.

## 8. AP-002 + AP-003 — Актуальная политика и отзыв сессионных разрешений

`core/agent/ApprovalGate`, настройки и store: общий инвариант актуального запрета при асинхронной записи и сессионных grants. Проверить перестановки load/write/emission, ошибки и reopen, DENY_ALL после ALLOW_SESSION в обеих policy и MCP. Переоценить DS-002.

## 9. DS-001 — Недоверенные ссылки на секреты в backup

`core/data/backup`, provider и MCP repositories, `core/provider/EndpointConfig`: не применять импортированные aliases. Проверить совпадающие id, локальный ключ, новый endpoint, все auth/header схемы и следующий HTTP; определить обработку прежних секретов.

## 10. UI-004 — Скрытые секретные заголовки при смене preset

После DS-001, `feature/settings/ProviderEditScreen`, `app/MainActivity`, `core/provider/EndpointConfig`: эффективный profile validation/save соответствует форме. Проверить mock HTTP до и после save; отдельный от импорта вход секрета.

## 11. DS-010 — Атомарная пара Basic credentials

`core/data/secret` и `provider`, `app/MainActivity`: согласованная запись username/password и ссылок. Проверить failure/cancel между частями и reopen со старой либо новой полной парой. Полная пара требует одного snapshot для чтения; repair неполных refs — публикации новой пары с существующим pending cleanup/warning. Настоящий disk/reopen проверять Android instrumentation, отдельно от JVM-тестов границ commit.

## 12. DS-011 — Device-protected backup domains

`app` backup XML и `core/data/DataContainer`: исключения реальных device storage roots. Проверить оба набора правил и D2D на подходящем OEM; перенос plaintext ключа не предполагается.

## 13. DS-012 — Секреты в экранированном JSON диагностики

`core/data/diagnostics`, `core/provider/ProviderErrorDiagnostic`: очистка известных секретов после JSON decode и raw fallback. Проверить error → store → export с Unicode/mixed escaping и невалидным JSON; повторить при DU-001.

## 14. TM-007 — Top-level политика локальных адресов web

`core/agent/WebFetcher`, `WebFetch`, `WebRender`: preflight до выбора tier и проверка static redirect до следующего запроса без auto-follow. Проверить public→private без второго запроса и render=always private до renderer. Политика WebView subresources/navigation и DNS rebinding вне подтверждённого объёма.

## 15. TM-006 — Ограниченное потоковое чтение web

После TM-007, `core/agent/WebFetch`: ограниченное чтение static и markdown response с закрытием. Проверить chunked/без length/большой length, отмену и лимит памяти, повторить policy tests.

## 16. TM-009 — Строгие аргументы memory_put

`core/agent/MemoryTools`: text и mode проверяются до записи. Проверить null, число, boolean, object, array, неизменность памяти и обычные append/replace.

## 17. AP-006 — Ошибка вместо исполнения повреждённого JSON

После TM-009, `core/agent/AgentLoop`: malformed/non-object возвращает tool error без вызова. Проверить счётчик исполнения, корректный `{}` и повторить TM-009: проверка object не заменяет проверку типа text.

## 18. TM-001 + TM-002 + TM-008 — MCP handshake и корреляция ответов

`core/agent/mcp/McpClient`, `McpDto`: единый протокольный state от negotiated initialize до корректного SSE response. Проверить notification без id, 202, stateless, ошибки initialize, версии/header, multiline data, progress, неверный id и heartbeat.

## 19. TM-003 + TM-004 — Полный каталог MCP с уникальными именами

После TM-001/002/008, `core/agent/mcp`, settings и config: собрать страницы и однозначно адресовать инструменты по полным идентификаторам. Проверить коллизии между серверами и страницами, длинные/sanitized имена, повтор cursor и failure; disabled names не должны случайно включиться.

## 20. TM-005 — Непустые результаты MCP

После TM-001/002/008 и TM-003/004, `core/agent/mcp`: поддерживаемое содержимое возвращать, неподдерживаемое обозначать явно. Проверить resource/link/image-only/mixed/structuredContent/isError; реализация всех optional типов не обязательна.

## 21. RT-003 — Ручной override маршрута

`core/provider/RoutingModelClient`, `app/di/AppGraph`: ручная настройка имеет приоритет над learned route. Проверить один клиент с изменяемым callback, probe и stream.

## 22. RT-004 — Достижимость поддерживаемых маршрутов

После RT-003, `core/provider/RoutingModelClient`: проверить все поддерживаемые маршруты при protocol-miss. Тест четвёртого Gemini и запрета fallback после чанка, auth error и отмены; конкретный маршрут агрегатора не предполагается.

## 23. RT-005 — TTL memory-cache models.dev

После RT-003/004, `core/provider/modelsdev`: применять TTL/backoff к памяти. Проверить управляемое время, stale, отказ и восстановление в одном экземпляре; оценить влияние на learned cache.

## 24. RT-001 + RT-002 — Полный каталог моделей с назначением

`core/provider/ModelCatalogFetcher`, модели и настройки: сохранить назначение и дочитать страницы одного снимка. Проверить mixed types, unknown purpose, Gemini/Anthropic pages, повтор cursor, error/cancel и отсутствие запроса для заведомо несовместимой модели; SystemOne не добавлять.

## 25. UI-013 — Владелец асинхронного каталога

После RT-001/002 переоценить `app/MainActivity` и `feature/settings/ModeConfigScreen`: поздний результат A не заменяет каталог B. Проверить refresh/probe A→B→поздний A и выбор модели B.

## 26. PW-002 — Неизменное продолжение Anthropic thinking

`core/provider/anthropic`, `core/model`, `core/agent`, история `core/data`: сохранять упорядоченные opaque blocks. Проверить SSE → loop → save/reopen → следующий POST для redacted, пустых и нескольких подписей; до миграции установить выпущенность формата.

Реализовано additive metadata без миграции формата: полные blocks с origin, сохранение через существующий codec, replay после reopen и защита от устаревшего snapshot при runtime-преобразованиях. Сквозные проверки включают обрыв, ошибку, отмену и смену провайдера/модели. Независимое ревью пройдено; PW-002 исправлен. Проверки HTTP/SSE выполнялись локально, live API не проверялся.

### PW-005 — Подписи Gemini text/empty (подтверждённый подшаг)

Контроль после PW-002 подтвердил потерю контекста Gemini text/chat по официальной публикации Google; HTTP 400 не заявляется. Полный legacy guide по empty carrier недоступен, способ сохранения ordered content fragments проверен по официальному Python SDK; источники и границы — в карточке PW-005. Выполнять отдельным свежим агентом до PW-006: additive metadata без миграции через существующую историю, сохранение подписанных/пустых частей и origin/domain guards; SSE → AgentLoop → disk/reopen → фактический следующий POST. Реализовано отдельным агентом; независимое review без блокирующих замечаний, релевантные тесты/Lint, сборка приложения и проверка model metadata пройдены. PW-005 исправлен отдельным изменением; server function ID остаётся PW-006. Live API не проверялся.

## 27. PW-006 — Server function ID Gemini

После PW-002 и контроля PW-005, `core/provider/gemini`, model/history при необходимости: сохранять серверный id отдельно от локального fallback. Проверить два одноимённых calls и matching functionResponse ids во втором запросе, а также случай без id.

PW-006 исправлен: additive nullable серверный ID через доменную историю, корреляция ответов по локальному ID без отправки synthetic fallback, проверка identity в replay. Регрессия на реальном HTTP/SSE воспроизведена; проверки включают tool errors, повторные раунды, disk/reopen, unsigned/id-less и stream failures. Unit-тесты model/provider/agent/data, релевантный Lint, сборка приложения и проверка model JAR пройдены; независимое ревью пройдено без замечаний. Live API не проверялся.

## 28. PW-003 — Возможности thinking Anthropic

После RT-001/002 и PW-002, `core/provider/anthropic` и capability model: manual/adaptive по проверенным возможностям endpoint/model. Проверить фактические тела manual, adaptive и unknown; не создавать статический каталог и не переносить native контракт на proxy.

## 29. PW-001 — Текст отказа OpenAI

После PW-002, `core/provider/openai` и agent/history: сохранять и показывать refusal. Проверить refusal-only и смешанные SSE Chat/Responses, UI/history и следующий запрос.

## 30. PW-004 — Причина блокировки Gemini

После PW-006, `core/provider/gemini` и consumer результата: отличать promptFeedback от обрыва. Проверить SAFETY/BLOCKLIST без candidates и настоящий преждевременный EOF.

## 31. AP-005 — Фильтрация изображений истории без VISION

После PW-002/006/001/004, `core/agent/RuntimeContext`, budgets и AgentLoop: фильтровать только отправляемый transcript, сохраняя исходную историю и metadata. Проверить user/tool images, обратное переключение и budget при VISION=true.

## 32. DS-008 — Атомарные файлы памяти и навыков

`core/data/memory`, `skill`: атомарная замена и сериализация read-modify-write, ошибка чтения не равна пустой памяти. Проверить write/read failure, конкурентные append и reopen.

## 33. DS-009 — Честное удаление памяти и навыков

После DS-008, `core/data/memory`, `skill`, backup и UI: сообщать частичный wipe и сохранять disabled flag при неудачном удалении. Проверить false/exception, retry и состояние после reopen.

## 34. DS-003 + DS-013 — Предвалидация и replace импорта

После AP-002/003, DS-001/010/008/009, `core/data/backup`, provider/prefs/skill и UI: проверить ограничения до мутаций, согласованно применить отсутствующие modes/catalogs и честно сообщать partial failure. Предварительно определить восстановление; глобальный rollback не предписан. Проверить empty skill до destructive шагов, legacy, late error/cancel/reopen и безопасную обработку секретов.

## 35. DS-004 + DS-005 — Восстановимый backup и лимит циклов

После DS-003/013, `core/data/backup`, prefs/memory: согласовать ограничения export/import и сохранить maxToolRounds. Проверить 65536/65537, общий размер, 0/1 и старый v1; search engine/chat sort не входят в подтверждённый объём.

## 36. DS-002 — Перенос разрешений из backup

После AP-002/003 и DS-003/013/004/005 определить перенос доверия, начиная с сохранения более строгих локальных разрешений; `core/data/backup`, agent approval и app UI. Проверить ALLOW поверх DENY/ASK, legacy, отмену и отсутствие исполнения до согласованного решения.

## 37. DS-006 + DS-007 — Согласованность media и ссылок БД

После PW-002 и DS-003/013, `core/data/chat`, media и ChatTurnSaver: failure записи не считается успехом, старые media удаляются после commit новых ссылок. Проверить write failure, Room ABORT, pending/retry, delete/clear, reopen и сохранность metadata.

## 38. UI-002 + UI-008 — Владелец и неизменный payload SHARE хода

После DS-006/007, `feature/share` и data handoff: привязать load/send/save к владельцу и snapshot загруженного payload. Проверить A→B, delete A, late load/save/Finish, медленный ContentProvider и совпадение отправленных и сохранённых изображений; включает UI-011.

## 39. AP-004 — История выполненных инструментов при ошибке

После PW-002, AP-005, DS-006/007 и UI-002/008 переоценить `core/agent`, chat/share/assistant consumers и data. Проверить внешний эффект → ошибка/отмена → reopen/следующий запрос во всех режимах, без дублей и выдуманного результата; добавить сквозной fixture metadata и partial history.

## 40. DU-001 — Долговечное владение диагностикой

После AP-004, `core/data/diagnostics` и agent operations: сохранять связь частного содержимого с владельцем независимо от потери START. Проверить dropped START → child → reopen → delete → полный export, повторить DS-012 и удаление media/chat.

## 41. AP-001 — Отмена ожидающего разрешения

`core/agent/PermissionRequestBridge`, `app/permission/PermissionSupport`: cleanup именно своего pending/blocked и late callback guard. Проверить отмену до/после START_TIMEOUT и повторный await.

## 42. UI-001 — Жизненный цикл результата разрешения

После AP-001, `feature/assistant` manifest и Activities: получить результат и завершать владельца без noHistory. Проверить grant/deny, overlay settings, recreation, отмену bridge и устройство.

## 43. UI-007 — Владение foreground service при acquire

`app/tasks/OAssistTaskService`, DI: устойчивый к отмене acquire/rollback. Проверить управляемую отмену между start и refcount, освобождение без владельца и сохранность других владельцев; повторить цепочки AP-001.

## 44. UI-009 — Регистрация VoiceInteractionService

`app` VIS XML/manifest и службы: проверить требования поддерживаемых SDK и корректно объявить sessionService и settings Activity. Проверить установленный parseError и системный вызов на устройстве; предусловие UI-010.

## 45. UI-010 — Контекст и screenshot VIS

После UI-009 и AD-009 переоценить `app/vis/OAssistVoiceInteractionService`: типизированный AssistState и session-bound screenshot. Проверить enabled/disabled screenshot, поздний callback, FLAG_SECURE и устройство.

## 46. SP-001 + SP-003 — Владелец микрофона и освобождение AudioRecord

`core/speech/VoiceListener` и assistant caller: режим/владелец захвата и exactly-once release одного ресурса. Проверить RAW→STT BUSY→закрытие, late old cancel, start/stop throw и новый capture; устройство отдельно.

## 47. SP-006 — Владение toggle STT экраном

После SP-001/003, `feature/chat` и speech handle: при уходе инвалидировать callback и отменять только свой capture. Проверить late final без network и сохранность чужой новой сессии; не использовать глобальный cancel.

## 48. SP-005 — Различение hold и toggle после permission

После AP-001, UI-001 и SP-001/003/006, `feature/chat/ChatScreen`: завершившийся hold не возобновляется grant callback. Проверить hold release→grant, toggle→grant один раз и поздний callback после навигации.

## 49. SP-004 — Ожидание TTS receipts

После SP-001/003, `core/speech/SpeechOutput` и assistant: ждать принятые TTS submissions, а не асинхронный UI state. Проверить задержанный collector, onDone, cancel/error и ограничение ожидания; повторить ownership tests.

## 50. SP-002 — Продолжение диалога после TalkBack

После SP-004 повторно оценить SCREEN_READER случай и рассмотреть ручное возобновление: announcement не даёт достоверный receipt завершения. `core/speech/ScreenReaderSpeaker` и assistant; проверить длинный ответ и отсутствие пересечения с микрофоном на TalkBack, не подменять подтверждение таймером.

## 51. UI-014 — Режим интерфейса активной сессии

После UI-001/007 и SP-001/003/002 рассмотреть фиксацию режима до конца активной сессии либо поддерживаемый переход. `feature/assistant/AssistantOverlayService`: проверить notification↔overlay при повторном вызове, доступность Stop/consent и отсутствие потерянного или дублированного управления.

## 52. UI-005 — Повтор CHAT при восстановлении сети

После AP-004 и SP-006, `feature/chat` и network state: pending retry наблюдает актуальный online у владельца. Проверить offline→settings→online→return ровно один send, без возобновления отменённого хода.

## 53. UI-003 + UI-006 — Нормализация входящего SHARE

После UI-002/008 и AP-004, `feature/share` и provider input: нормализовать MIME и CharSequence, явно отказывать неподдерживаемому содержимому. Проверить String/SpannedString/PDF/unknown и фактический запрос; PDF parser/OCR необязателен.

## 54. UI-012 — Системные insets SHARE

После UI-003/006, `feature/share` и `core/ui`: safe insets и IME без двойного padding. Проверить Android 15/16, жестовую и трёхкнопочную навигацию, вырез, клавиатуру и TalkBack; прежняя геометрия не измерялась.

## 55. AD-004 — Достоверный результат DND

`core/agent/DeviceActions`, `SoundTools`: учитывать API 35+ и фактический filter без обещания глобального выключения. Проверить API 34/35+ с ручным и другим правилом на устройстве; сохранить approval.

## 56. AD-007 — Полный или явно отклонённый notify reply

`core/agent/NotifyActions`, `NotifyTools`: не обрезать сообщение молча. Проверить 500/501, Unicode, отсутствие PendingIntent при отказе и реальную notification на устройстве.

## 57. TM-010 — Разбор известных поисковых redirect

`core/agent/SearchParsers`: разворачивать только известные host/path. Проверить прямые q/faq, encoded q и настоящий redirect; актуальная live-вёрстка не установлена.

## 58. DU-002 — Очистка старых APK и частей

`app/update/UpdateManager`, `core/net/update`: ограниченная политика обслуживания после перезапуска, не затрагивающая активную загрузку или FileProvider handoff. Проверить остатки после смерти процесса, активный download и переданный установщику APK.

После очереди сравнить полный diff с актуальной целевой веткой без неразрешённого fetch и провести независимую сквозную проверку: prompt → request → approval → Android/MCP tool → partial failure → persist/reopen → speech. Выполнить расширенные релевантные build/integration проверки и отдельно учесть открытые аппаратные/live gates. Это не повторный полный аудит и не подтверждение работы каждого endpoint агрегатора.

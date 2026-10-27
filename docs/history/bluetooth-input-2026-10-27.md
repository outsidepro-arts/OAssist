# Bluetooth-гарнитуры для записи, 2026-10-27

## Задача и решения пользователя

Поддержка Bluetooth-гарнитур как источника голосового ввода. Решения при планировании:

- **Объём — только запись (микрофон).** Вывод (TTS/звуки) маршрутом не управляется;
  A2DP-вывод работает без правок.
- **AUTO-маршрут:** при подключённой BT-гарнитуре писать с неё, иначе — микрофон.
  Системный запрос `BLUETOOTH_CONNECT` — **один раз**; при отказе больше не спрашиваем,
  работаем с микрофона.
- **Настройка per-mode** (`SttConfig.bluetoothInput`, дефолт `true`), как у
  RecognitionService. Старые JSON без поля декодируются в `true`.
- **Маршрут поднимается и для SYSTEM-STT** (до `startListening()`), чтобы сторонние
  RecognitionService получали BT-микрофон.

## Что реализовано

- `core/speech/VoiceInputRouter.kt` — чистая логика: `BtInputDecision`
  (`USE_BLUETOOTH | USE_MIC | ASK_PERMISSION`), маршрут на сессию захвата
  (`acquire`/`release`, идемпотентный release), потеря гарнитуры mid-capture —
  откат в микрофон с переходом `route`. Логгер инжектируемый (no-op в JVM-тестах,
  `OAssistLog` в проде — конвенция «чистые классы не трогают android.util.Log»).
- `core/speech/AndroidVoiceRouteBackend.kt` — `setCommunicationDevice`/`clearCommunicationDevice`
  (API 31 = minSdk), поиск BT-входа по `TYPE_BLUETOOTH_SCO`/`TYPE_BLE_HEADSET`,
  `AudioDeviceCallback` для потери устройства. Там же `SharedPrefsBtPermissionFlag`
  (device-local флаг «разрешение уже запрашивали», SharedPreferences `voice_input`;
  не конфиг пользователя — DataStore не требуется).
- `VoiceListener`: маршрут захватывается в `startStt`/`startRaw` (через retry-политику
  STT — до конца сессии), снимается в `finishSttSession`/`stopRaw`/`cancelRaw`.
  Порядок при конце: сначала `state=Idle`, потом `release` — UI отличает штатный конец
  от фолбэка (route → микрофон при ещё активном `Listening`).
- `VoiceHandle`: `routeState()`, `bluetoothInputDecision()`, `markBluetoothPermissionRequested()`,
  параметр `bluetoothInput` в `startStt`/`startRaw`. Тулы `voice_listen_*` идут с
  дефолтом `true` (AUTO).
- Чат (`ChatScreen`): `BLUETOOTH_CONNECT` запрашивается при первом захвате с
  подключённой гарнитурой (toggle — старт возобновляется после диалога; hold — нет,
  палец уже поднят). Отказ не блокирует запись. Фолбэк mid-capture — Toast.
- Оверлей (`AssistantOverlayService`): флаг из конфига режима; фолбэк — hintText
  в панели/уведомлении. **Не запрашивает разрешение** (сервис без Activity): пока
  разрешение не выдано из чата/настроек — микрофон.
- Настройки (`ModeConfigScreen`): свитчер «Bluetooth-гарнитура для записи»
  (`ModeSwitch`, per-mode), запрос разрешения при включении, статус
  `OAssistLiveStatus` «Без разрешения Bluetooth запись идёт с микрофона».
- Манифест: `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS`,
  `uses-feature android.hardware.bluetooth required="false"`.

## Осознанные компромиссы

- **SCO-задержка не выжидается**: `setCommunicationDevice` принимает маршрут, но SCO
  поднимается сотни мс — начало записи может быть тихим/худшего качества. Полная
  машина ожидания готовности с таймаутом не реализована; если устройство покажет
  проблему — дорабатывать здесь.
- **На время SCO-записи вывод уходит в гарнитуру** в низком качестве
  (`setCommunicationDevice` действует и на вывод). Запись короткая (PTT/hold) —
  компромисс принят.
- Ручной выбор гарнитуры (несколько устройств — берётся первая из `getDevices`),
  LE Audio-верификация, отдельный маршрут вывода — отложены.

## Диагностика первого теста на устройстве (2026-10-27)

Пользовательский тест нашёл, что маршрут не работает вообще: SCO («разговорный»
профиль) не поднимается (по звуку скринридера), микрофон выключенной гарнитуры
не глушит захват — аудио шло с микрофона телефона. Оверлей не запрашивал
`BLUETOOTH_CONNECT` (запросился только из чата).

**Корневая причина маршрута — контракт `setCommunicationDevice`**: метод принимает
только **sink** (выходное) устройство из `getAvailableCommunicationDevices()`,
а соответствующий источник (микрофон гарнитуры) платформа выбирает сама. Мы
передавали input-устройство из `getDevices(GET_DEVICES_INPUTS)` — запрос молча
отклонялся (`false`), маршрут падал на микрофон. Пофиксено: выбор BT-sink
(`TYPE_BLUETOOTH_SCO`/`TYPE_BLE_HEADSET`, без A2DP-колонок) + страховочное
`AudioRecord.setPreferredDevice` на BT-вход для RAW + диагностика в лог
(`VoiceRoute`: принятость запроса и активное устройство; `OAssistRaw`: фактическое
устройство записи; `VoiceInputRouter`: выбранный маршрут и причина микрофона).

**Оверлей**: сервис не может показать системный диалог — запрос теперь идёт через
существующий механизм тулов (`ConsentActivity` + `PermissionRequestBridge`):
translucent-активность поверх оверлея, при заблокированном старте окна — фолбэк
«Выдать» в сессионном уведомлении. Политика «спросить один раз» сохранена; после
диалога старт возобновляется только если состояние слушания не сменилось.

**Открытый риск — STT-путь**: `SpeechRecognizer` выполняется в чужом процессе
(движок распознавания), его маршрутизацию наше приложение не контролирует.
Ожидание: поднятый нашим маршрутом SCO переводит общий вход на гарнитуру и
движок берёт BT-микрофон; если на практике нет — следующий рычаг `MODE_IN_COMMUNICATION`
на время захвата (не включён сознательно: глобальный audio mode + восстановление).
Для MODEL_AUDIO (RAW) источник под нашим контролем — там маршрут должен работать
напрямую.

**Проверки 2026-10-27 (после фикса):** JVM speech (router), compile
speech/assistant/app, lint speech/assistant. Повторный тест на устройстве:
пользователь подтвердил запись с микрофона гарнитуры. Остаётся неподтверждённым:
запрос разрешения из оверлея (и фолбэк в уведомлении), смена звука при SCO,
фолбэк при отключении mid-capture; какой именно путь (STT/RAW) проверен —
не уточнялось, STT-движок в чужом процессе остаётся риском для сторонних
распознавателей.

## Проверки 2026-10-27

- compile: `core:speech`, `core:data`, `feature:chat`, `feature:settings`,
  `feature:assistant`, `:app`.
- JVM: `VoiceInputRouterTest` (10 сценариев: policy, acquire/release, фолбэк,
  stale-callback, идемпотентность), `ProviderConfigCodecTest`
  (round-trip с `bluetoothInput=false`, legacy без поля → `true`), `feature:chat`.
- `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin`.
- lintDebug: `core:speech`, `feature:chat`, `feature:settings`, `feature:assistant`, `:app`.

**Не проверено (нет устройства с BT-гарнитурой):** фактическая запись с гарнитуры
(STT и RAW), поведение сторонних RecognitionService с поднятым SCO, задержка SCO
на первых мс записи, фолбэк при отключении mid-capture, сценарий однократного
запроса разрешения на устройстве, TalkBack-проход новых статусов.

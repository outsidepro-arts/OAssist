# Авто-именование чата с аудио-первым сообщением MODEL_AUDIO, 2026-10-27

## Проблема и причина

Если чат начинался сообщением, где STT — аудио для модели (RAW MODEL_AUDIO), диалог
не получал автоматического имени. Причина: в этом пути голосовое отправляется как
`send("", AudioPart(...))` без текста (`ChatScreen.stopHold`), поэтому:

- `ChatTitler.placeholder("")` возвращал пустую строку — чат создавался с пустым именем;
- `ChatTitler.generateTitle` выходил на `firstMessage.isBlank()` и возвращал null —
  запрос к модели для названия не запускался;
- фолбэк `saveCurrentChat` брал `turns.first().userText`, который тоже пуст.

Текстового транскрипта аудио в этом пути нет: единственный текстовый материал на
момент записи хода — первый ответ модели.

## Решение (с выбором пользователя)

- **Seed названия для аудио-хода — первый ответ модели.** `ChatTitler.generateTitle`
  получил параметр `firstAnswer` (дефолт `""`): seed = `firstMessage.ifBlank { firstAnswer }`;
  при пустом тексте пользователя промпт модели — «по ответу ассистента на аудиосообщение».
- **Без ответа (ошибка/отмена) имя остаётся пустым** — фолбэка «Голосовое сообщение»
  пользователь не выбрал, новые строки не добавлялись.
- Плейсхолдер в `recordTurn` строится от `userMsg.ifBlank { answer }`; фолбэк
  `saveCurrentChat` — первый непустой `userText` среди ходов, иначе первый непустой `answer`.

## Изменённые файлы

- `core/agent/ChatTitler.kt` — параметр `firstAnswer`, два варианта промпта.
- `feature/chat/ChatScreen.kt` — `generateTitle(userMsg, answer)`, `nameSeed` в
  `recordTurn`, фолбэк-имя в `saveCurrentChat`.
- `core/agent/src/test/.../ChatTitlerTest.kt` — сценарии: аудио-ход именуется по ответу
  (с проверкой промпта), пустые сообщение+ответ → null; fake-клиент захватывает промпт.

## Проверки

- `:core:agent:testDebugUnitTest`, `:feature:chat:testDebugUnitTest` — без ошибок.
- `:app:assembleDebug`, `:core:agent:lintDebug`, `:feature:chat:lintDebug` — без ошибок
  (предупреждения deprecation в `ShareActivity` прежние, не из этой правки).
- **Устройство (21121210G, Android 14):** пользователь подтвердил сценарий — чат с
  голосовым MODEL_AUDIO первым сообщением получает название от модели. APK устанавливался
  через `:app:installDebug`. TalkBack-проход не требовался: изменение не затрагивает
  UI-элементы (только логика именования).

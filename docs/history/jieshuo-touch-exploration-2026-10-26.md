# Нажатия при Jieshuo browse by touch — 2026-10-26

## Границы диагностики

Физический Mi 10, Android 16/API 36. На телефоне запущен Jieshuo; TalkBack
не участвовал в этих проверках. Shizuku/root приложению не предоставлялись.
Установленные DEX-файлы OAssist совпали с локальным APK ветки
`fix/unprivileged-action-fallbacks` на `057a87f`.

OAssist не удалялся и не переустанавливался, данные не очищались. Во время
диагностики сообщения не отправлялись. Исходные копии сохранённой сессии и
структуры экрана остались вне Git; персональные данные сюда не перенесены.

## Подтверждённые наблюдения

- Пользователь повторил сценарий: с Jieshuo browse by touch, выключенным
  вручную, переход в чат прошёл; после включения сценарий снова не прошёл.
  Это проверка зависимости от режима исследования касанием, а не доказательство
  конкретной внутренней причины в Jieshuo или Android.
- В сохранённой сессии `ui_click` возвращал успех с пометкой «жест доступности»;
  следующий `ui_get_screen` продолжал показывать поиск. Ошибка активации цели
  не была ошибкой выполнения этого инструмента.
- У строки результата `isClickable=false`, но в `actionList` есть ACTION_CLICK.
  Прямой вызов `performAction(ACTION_CLICK)` из установленного сервиса OAssist
  через JDWP вернул false. Одного снятия проверки isClickable для этой строки
  недостаточно.
- В последней сохранённой попытке результат `ui_screenshot` содержит пометку,
  что изображение не передано модели из-за отсутствия поддержки изображений.
  Последний выбранный после этого индекс относился к Switch, а не строке чата.
  Фактические возможности модели отдельно не проверялись.
- Проверка с временным отключением только собственного accessibility-кэша
  OAssist также показывала поиск; исходное состояние кэша восстановлено.

## Границы экспериментальных проверок

Через существующий debug-процесс проверялись отдельные Android API, без замены
APK и повышения привилегий. Попытки с фокусом/системным D-pad не дали подтверждения
открытия чата. Эти эксперименты не являются проверенным решением.

Контроль двойного касания не завершился чистой проверкой callback и результата:
в ходе JDWP-диагностики появился ANR OAssist. В events-логе зарегистрирован
`Input dispatching timed out`, ожидание `TouchModeEvent(inTouchMode=true)`
5001 мс. Отладочные остановки могли повлиять на выполнение, поэтому это не
доказательство независимой ошибки приложения или результата двойного касания.

Все отладочные остановки сняты, отладчик отключён, task-owned ADB forwarding
удалён. Тот же процесс OAssist после отключения отвечал на запрос дампа сервиса.
Дальнейшие проверки на телефоне не должны останавливать его потоки.

## Сопоставление с Android API

- [dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler))
  допускает влияние touch exploration. Завершение инъекции не подтверждает
  активацию элемента приложения.
- [TouchInteractionController.performClick](https://developer.android.com/reference/android/accessibilityservice/TouchInteractionController#performClick())
  описан для сервиса с запросом touch exploration и зарегистрированным callback.
  Это не универсальная замена координатного клика пассивного сервиса.
- [TalkBack FocusActor](https://github.com/google/talkback/blob/master/talkback/src/main/java/com/google/android/accessibility/talkback/actor/FocusActor.java)
  использовался только как справочник: проверка доступного ACTION_CLICK,
  контроллер в соответствующем режиме и резерв с двумя касаниями. Совпадение
  этого поведения с Jieshuo не проверено.
- `setTouchExplorationPassthroughRegion` меняет область на дисплее, а публичного
  getter для восстановления чужой области нет. В этой диагностике область
  не менялась; безопасное совместное использование не установлено.

## Отличие от adb shell input tap

После первоначальной диагностики пользователь проверил `adb shell input tap`
в центре экрана при запущенном Jieshuo: команда активирует значок лаунчера,
элемент в чате и результат поиска. Состояние browse by touch в этом сообщении
сопоставлялось с ранее воспроизведённым отказом OAssist. Это самостоятельная
проверка пользователя; агент команду касания не выполнял.

Исходники AOSP Android 16 объясняют различие путей:

- [InputShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/input/InputShellCommand.java)
  создаёт DOWN/UP и вызывает InputManagerGlobal.injectInputEvent.
  [InputManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/input/InputManagerService.java)
  требует INJECT_EVENTS и передаёт событие в native-инъекцию.
  [InputDispatcher::injectInputEvent](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/dispatcher/InputDispatcher.cpp)
  помещает его в очередь диспетчера без вызова accessibility InputFilter.
- [MotionEventInjector](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java)
  обслуживает dispatchGesture и передаёт события следующему преобразованию.
  [AccessibilityInputFilter](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/accessibility/java/com/android/server/accessibility/AccessibilityInputFilter.java)
  ставит injector перед TouchExplorer при включённом touch exploration.

Таким образом, одинаковые координаты не означают одинаковую доставку события.
Запуск `input tap` из обычного процесса приложения не даёт ему shell UID или
INJECT_EVENTS. Существующий Shizuku/root-исполнитель предоставляет другой
контекст исполнения; новая попытка через AccessibilityService его не заменяет
во всех состояниях экранного чтеца.

Дополнительная граница текущей цепочки: performGestureWithFallback возвращает
успех после успешного callback. Если инъекция завершилась, но цель не активирована,
привилегированный input tap уже не вызывается, даже при наличии исполнителя.
Изменение маршрутизации или проверка результата активации — отдельное исправление;
его здесь не реализовывали.

Рабочее исправление взаимодействия с browse by touch пока не реализовано.
Актуальные незавершённые проверки — в [MEMORY.md](../../MEMORY.md).

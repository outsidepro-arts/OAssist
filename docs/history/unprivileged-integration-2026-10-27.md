# Squash-интеграция unprivileged-ветки — 2026-10-27

## Итоговый объём

По явному запросу пользователя подготовлен squash ветки
`fix/unprivileged-action-fallbacks` (`7ddf1be`) поверх актуального удалённого
`master` (`4abdf49`). Ветка `fix/runtime-prompts` в этот коммит не включена.

- `shell_get_setting` и `shell_put_setting` сначала используют Android Settings API
  с правами приложения; при отказе или `putString=false` остаётся Shizuku/root.
- Отсутствующие и пустые значения не запускают резерв, отмена корутины сохраняется.
  Минимальная привилегия этих инструментов — NONE, группы approval не меняются.
- `ui_long_click` проверяет `isLongClickable`. Дополнительный координатный
  Accessibility-жест остаётся отменённым после диагностики Jieshuo; порядок —
  действие узла, затем прежний привилегированный input. Скрытие оверлея сохранено.
- Звуки записи из актуального master сохранены. Исторические описания промежуточного
  жестового решения не отменяют поздний отказ в MEMORY.md и записи Jieshuo.

## Проверка объединённого состояния

```powershell
.\gradlew.bat :core:agent:testDebugUnitTest :core:speech:testDebugUnitTest :feature:chat:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

442 JVM-теста: agent 361, speech 76, chat 5; ошибок и пропусков нет.
Debug APK и app lintDebug успешны; lint: 35 предупреждений, 3 подсказки, ошибок нет.
Независимый reviewer проверил итоговый staged diff, сохранение звуков, отсутствие
runtime-промптов и отменённого жеста; блокеров не найдено. Перед коммитом проверен
staged diff и отсутствие неотносящихся файлов в индексе.

Первый запуск в Windows sandbox остановился на невозможности удалить
`core/speech/build/kotlin/compileDebugUnitTestKotlin/cacheable/last-build.bin`.
Повтор той же команды вне sandbox успешен; настройки сборки, ACL и общие кэши
не менялись.

Устройство, TalkBack и instrumentation повторно не проверялись. Прежние
3 instrumentation-теста Settings Provider не подтверждают реальную запись
с WRITE_SETTINGS или привилегированный резерв на разных прошивках.

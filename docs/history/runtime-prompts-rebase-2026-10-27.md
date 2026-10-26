# Rebase ветки runtime-промптов — 2026-10-27

## Основа и сохранение изменений

После squash-интеграции `fix/unprivileged-action-fallbacks` коммит `ea076a3`
отправлен в `origin/master`. По явному запросу пользователя три коммита
`fix/runtime-prompts` перенесены с `d2c11da` на этот master:

| Исходный коммит | После rebase | Назначение |
|---|---|---|
| `1949a5f` | `a3e1faa` | Изоляция генерации названий |
| `2a2061b` | `0c5c003` | Разделение runtime-инструкций и данных |
| `abbc60f` | `b2faf68` | Общие runtime-правила |

Исходный HEAD `abbc60f` сохранён локально в
`backup/runtime-prompts-before-rebase-20261027`. Runtime-ветка не влита
в master; её remote-ссылка не переписывалась.

Конфликты каждого из трёх коммитов касались только таблицы проверок
`MEMORY.md`: сохранены записи с обеих сторон. `range-diff` подтвердил, что
отличия патчей ограничены контекстом документации; исходники и тесты перенесены
без изменения. Отдельное независимое review подтвердило сохранение Settings API,
отката дополнительного Accessibility-жеста и звуков из master. `diff --check` чистый.

## Проверка результата

Проверен точный снимок кода `b2faf68`, экспортированный через `git archive`:

```powershell
.\gradlew.bat :core:agent:testDebugUnitTest :core:data:testDebugUnitTest :core:provider:testDebugUnitTest :core:model:testDebugUnitTest :core:speech:testDebugUnitTest :feature:chat:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

719 JVM-тестов: agent 382, data 52, provider 162, model 42, speech 76, chat 5.
Ошибок и пропусков нет. Debug APK собран; app lintDebug успешен:
35 предупреждений, 3 подсказки, ошибок нет.

Первоначальная проверка в основной рабочей папке, запущенная вне Windows sandbox,
остановилась при записи и чтении существующего
`core/agent/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/META-INF/agent.kotlin_module`.
Kotlin также сообщил `AccessDeniedException` при возврате файла из своего
временного backup. Чтение ACL вне sandbox показало владельца текущего пользователя
и его FullControl; это не устанавливает причину отказа и не исключает блокировки
файла. Права, настройки сборки и общие кэши не менялись; исходные build-артефакты
не удалялись. В свежем временном каталоге та же проверка завершилась успешно.
Ошибка доступа основной рабочей папки не объявляется исправленной.

Проверки устройства, TalkBack, реальных провайдеров и adversarial-сценариев
не проводились. Открытые ограничения сохранены в MEMORY.md.

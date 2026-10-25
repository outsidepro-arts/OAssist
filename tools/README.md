# tools/ — запуск сборок (Windows)

Основной интерфейс сборки — Gradle Wrapper. Скрипты здесь служат необязательными
синхронными обёртками: показывают вывод Gradle, ждут завершения и возвращают его код.
Они наследуют JDK/SDK и GRADLE_OPTS из окружения, не завершают другие процессы.

## Сборка и тесты

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug

.\tools\run-build.ps1
.\tools\run-build.ps1 start -Task ':app:lintDebug'
.\tools\run-tests.ps1 -Tasks ':core:agent:testDebugUnitTest'
.\tools\run-tests.ps1 -Tasks ':core:model:testDebugUnitTest', ':core:data:testDebugUnitTest'
```

Обёртки выбирают корень проекта независимо от текущего каталога. На Unix используйте
`./gradlew` напрямую.

Действия `status`, `stop`, `clean-orphans` и параметр `-TimeoutSec` удалены:
сборка выполняется в текущем вызове, без фонового процесса и PID-файлов.
Timeout настраивается у вызывающего инструмента. Принудительное завершение всех
Gradle/Kotlin daemon перед запуском больше не выполняется.

## Релиз

```powershell
.\tools\gen-keystore.ps1
.\tools\run-build.ps1 release
.\tools\run-build.ps1 verify-release
```

Команда `release` по-прежнему требует `keystore.properties`. Прямой вызов
`gradlew.bat :app:assembleRelease` без этого файла формирует неподписанный APK.
Логика ключей и подписи не менялась.

`verify-release` выбирает SDK через `-SdkRoot`, затем `ANDROID_HOME` и
`ANDROID_SDK_ROOT`. Если Gradle настроен только через `sdk.dir` в
`local.properties`, передайте тот же SDK через `-SdkRoot`.

APK: `app/build/outputs/apk/release/app-release.apk`.
Храните резервные копии ключа и `keystore.properties` вне Git.

## Настройки выполнения

Gradle и Kotlin используют штатные демоны. Параллельное выполнение проектов и build
cache включены; число workers выбирает Gradle по доступным процессорам.
Лимиты heap/metaspace сохранены. Configuration cache не включается принудительно:
его совместимость с полным набором задач требует отдельной проверки.

Личные ограничения памяти/workers задавайте в `GRADLE_USER_HOME/gradle.properties`.
Файловое наблюдение использует стандартную политику Gradle. При ошибках watcher на
неподдерживаемой файловой системе (например, ReFS/Dev Drive) можно локально задать:

```properties
org.gradle.vfs.watch=false
```

Согласовывать JVM-аргументы клиента с аргументами демона не требуется.
Живые демоны после завершения сборки предназначены для повторного использования.

## Локальная диагностика OpenCode

Ранние ограничения сборки были обходами проблем инструментальных вызовов OpenCode
на Windows. Они не являются требованиями проекта. Сначала сравните ту же команду
в обычной PowerShell и в OpenCode, запишите версию клиента и его логи.

Если Gradle уже завершился, а OpenCode ждёт вывод команды, проверьте перенаправление
stdout/stderr в файл. Например, следующий локальный .ps1 запускается из корня проекта
и сохраняет код завершения:

```powershell
cmd.exe /d /c '.\gradlew.bat :app:assembleDebug --console=plain > "%TEMP%\oassist-build.log" 2>&1 < NUL'
$code = $LASTEXITCODE
Get-Content -LiteralPath "$env:TEMP\oassist-build.log" -Tail 20 -Encoding utf8
exit $code
```

Это диагностический обход, а не подтверждённое исправление backend OpenCode.
При нескольких одновременных проверках используйте разные файлы логов.

В OpenCode 1.19.4 timeout shell-команды по умолчанию равен двум минутам. Увеличивайте
timeout именно вызова сборки. Локальная переменная
`OPENCODE_EXPERIMENTAL_BASH_DEFAULT_TIMEOUT_MS` задаёт значение по умолчанию;
Desktop должен унаследовать её при запуске. Это не таймаут запроса к модели.

Если перестаёт отвечать весь backend, отдельно проверяйте плагины и watcher OpenCode.
В личном конфиге поддерживаются `shell: "pwsh"` и `watcher.ignore` с шаблонами
`**/build/**`, `**/.gradle/**`, `**/.kotlin/**`. Не меняйте все параметры одновременно.

Источники: [OpenCode 1.19.4 shell](https://github.com/anomalyco/opencode/blob/v1.19.4/packages/opencode/src/tool/shell.ts),
[конфигурация OpenCode](https://opencode.ai/docs/config/),
[диагностика OpenCode](https://opencode.ai/docs/troubleshooting/),
[Gradle file system watching](https://docs.gradle.org/current/userguide/file_system_watching.html).

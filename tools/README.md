# tools/ — управляемый запуск сборок (Windows)

Проблема: на Windows каждая Gradle-сборка порождает несколько JVM (single-use daemon,
Kotlin daemon, тестовая JVM). При жёстком таймауте tool-call’а они остаются висеть
и удерживают handles — вызов никогда не завершается. Поэтому тяжёлые задачи
запускаются detached, а завершение отслеживается опросом.

## Тяжёлые задачи (`assemble*`, `lint*`, полные прогоны)

```powershell
.\tools\run-build.ps1 clean-orphans            # убить сирот-демонов перед запуском
.\tools\run-build.ps1 start -Task ':app:assembleDebug'
.\tools\run-build.ps1 status                   # опрос: pid/alive + хвост лога
.\tools\run-build.ps1 stop                     # tree-kill (taskkill /T /F)
```

Лог: `%TEMP%\opencode\gradle-build.log` (stdout), `gradle-build.err.log` (stderr).

Порядок: `clean-orphans` → `start` → отдельными tool-call’ами `status` каждые 30–60 с
до `BUILD SUCCESSFUL`/`BUILD FAILED` → при необходимости `stop` → контроль сирот.

**Никогда не запускать `assemble*`/`lint*` одним синхронным tool-call’ом** —
он молчит дольше пары минут, harness убивает процесс, а JVM-сироты остаются.

## Релиз (подписанный APK)

```powershell
.\tools\gen-keystore.ps1                 # один раз: keystore/ + keystore.properties (в .gitignore)
.\tools\run-build.ps1 clean-orphans
.\tools\run-build.ps1 release             # detached :app:assembleRelease (подпись из keystore.properties)
.\tools\run-build.ps1 status              # опрос до BUILD SUCCESSFUL
.\tools\run-build.ps1 verify-release      # apksigner verify --print-certs
```

APK: `app\build\outputs\apk\release\app-release.apk`. Пароли и ключ — только в
`keystore.properties`/`keystore/`, в git не попадают; без них Gradle собирает
релиз **без подписи** (с warning). Перед релизом прогнать линт:
`.\tools\run-build.ps1 start -Task ':app:lintDebug'`.

**Ключ потерял — апдейты подписать нельзя** (подпись v2/v3 приложения должна
совпадать): бэкапить `keystore/` и `keystore.properties` вне репозитория.

## Юнит-тесты (короткие задачи)

```powershell
.\tools\run-tests.ps1 -Tasks ':core:agent:testDebugUnitTest' -TimeoutSec 300
```

Синхронно, с таймаутом и tree-kill по его истечении; сироты-демоны чистятся до запуска.
Лог: `%TEMP%\opencode\gradle-tests.log`.

## Почему настроено так

- Сборка идёт **в клиентском JVM** (ноль форк-демонов). Для этого
  `org.gradle.jvmargs` в `gradle.properties` и `GRADLE_OPTS` в этих скриптах
  обязаны совпадать **в точности** — иначе Gradle форкает single-use daemon
  (docs: "If the JAVA_OPTS and GRADLE_OPTS match org.gradle.jvmargs, the Daemon
  will not be used at all"). Состав строки не случаен:
  - `-Xms64m` — `gradlew.bat` всегда добавляет `DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"`;
  - `--add-modules=ALL-DEFAULT` — `java.exe` этого JDK инжектирует флаг в каждый запуск;
  - `org.gradle.internal.instrumentation.agent=false` — клиент запущен без
    `-javaagent`, без этого флага mismatch agent-статуса форкает single-use daemon.
- `org.gradle.daemon=false`, `kotlin.compiler.execution.strategy=in-process`,
  `options.fork = false`, `maxParallelForks = 1` — минимум JVM на сборку.
- Запрещено: `--offline`, `--no-build-cache`, `clean` (см. AGENTS.md).

# Android-проверка Koog

Ветка: `codex/koog-compatibility`. Main и основной сетевой путь не изменяются.

План:
1. Kotlin 2.3.10 + AGP 8.13.2: собрать приложение, выполнить unit-тесты и lint.
2. Подключать Koog только при `-PkoogProbe` в debug/test конфигурациях.
3. Выполнить общий HTTP-набор на JVM и на Android API 35. Сервер слушает только
   127.0.0.1; ключ, задача, reasoning и ответы синтетические.
4. Проверить запуск тестового приложения с сохранением данных, выполнить ревью
   и зафиксировать результаты. Не менять рабочий путь генерации.

## Решения

- Используется готовый `http-client-ktor` и его OkHttp engine. У прямого
  `http-client-okhttp` 1.3.0 в исходниках обычный POST использует блокирующий
  `Call.execute()` без привязки к отмене coroutine; для приложения отмена обязательна.
  Это выбор по исходникам, а не измеренное сравнение двух транспортов.
- Kotlin/Compose compiler обновляются вместе через единый version catalog.
  AGP 8.13.2 выбран ради поддержки Kotlin 2.3. Gradle остаётся 8.14.
- Koog/Ktor подтягивает OkHttp 5.2.1, поэтому MockWebServer тоже 5.2.1.
  Несогласованный MockWebServer 4.12.0 падал с `NoClassDefFoundError: okhttp3.internal.Util`.
- Отключённый SLF4J backend предотвращает запись содержимого запросов в логи стенда.

## JVM HTTP-проверка

Команда: `./gradlew.bat -p experiments/koog-compatibility test --console=plain`.
Получено 10 успешных тестов (6 сериализации + 4 HTTP).
Публичный `execute` проверен для thinking on/off + JSON, последовательности
search_examples/read_example/answer с переносом reasoning, HTTP 429 без повтора,
отмены (менее 1.5 с при задержке заголовков 4 с).

В этой последовательности результаты инструментов синтетические: она проверяет
протокол клиента, не интеграцию Koog с нашей реальной библиотекой и checkpoint.

Клиент требует `system_fingerprint` в ответе DeepSeek: отсутствие поля приводит
к `MissingFieldException`. Это ограничивает совместимость с произвольными
OpenAI-совместимыми endpoints при выборе клиента DeepSeek.

Источники:
- https://developer.android.com/build/releases/agp-8-13-0-release-notes
- https://docs.koog.ai/prompts/http-clients/

## Команды Android

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -PisolatedDeviceCheck -PkoogProbe --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.smartcalc.experiments.koog.KoogHttpContractTest com.smartcalc.app.importcheck.test/androidx.test.runner.AndroidJUnitRunner
```

`install -r` обновляет существующую тестовую установку без очистки данных.
Не используйте uninstall/clear для выполнения этой проверки.
`-PkoogProbe` без `-PisolatedDeviceCheck` намеренно отклоняется сборкой.
Основной transport приложения остаётся прежним: SDK доступен в debug APK,
но вызывается только тестами. Release не получает Koog/Ktor из этого флага.

## Итог проверки 2026-10-14

- Kotlin 2.3.10 / AGP 8.13.2: приложение собрано. Новый lint выявил только
  неэкранированное двоеточие пути SDK в локальном `local.properties`;
  исправлено на `sdk.dir=D\:/DevTools/Android/Sdk` (файл не в Git).
- С `koogProbe`: assembleDebug, assembleDebugAndroidTest, testDebugUnitTest,
  lintDebug — BUILD SUCCESSFUL. 335 unit-тестов, 0 ошибок. Предупреждения lint
  остаются; проверка завершилась без ошибок.
- Тот же HTTP-набор на Android API 35: OK (4 tests), 1.69 секунды.
- Повторный JVM-набор после проверки точного HTTP-статуса: 10 тестов успешны.
- Холодный запуск тестового приложения через MainActivity успешен; экран
  калькулятора отображается. Полный визуальный регресс всех экранов не проводился.
- APK debug с новым toolchain до Koog: 22 886 570 байт; с экспериментом:
  28 247 344 байта. Это сравнение debug-сборок без минификации, не release-оценка.
- Проверка защиты: `help -PkoogProbe` без isolatedDeviceCheck отклонён ожидаемым сообщением.
  releaseRuntimeClasspath с включённым флагом не содержит Koog/Ktor.
- Независимое read-only ревью: подтверждённых дефектов изоляции и тестов нет.
  Устаревшее описание границ проверки в README обновлено.

Рабочий транспорт не переключён. Перед интеграцией остаются адаптация данных
нашего LibraryAgentLoop/checkpoint, очистка сообщений ошибок SDK (они могут
содержать тело ответа), проверка фотографий через публичный API и реальных
провайдеров, а также проверка минимального поддерживаемого Android API 26.
API 35 не доказывает работоспособность на всех поддерживаемых устройствах.

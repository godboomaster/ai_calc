# Проверка Koog для SmartCalc

Изолированный JVM-проект: не включён в корневой `settings.gradle.kts`, не меняет APK,
не читает настройки приложения и не использует API-ключи. Транспорт заглушён:
любое обращение к нему завершает тест ошибкой.

## Запуск

Из корня репозитория:

```powershell
./gradlew.bat -p experiments/koog-compatibility test --console=plain
```

Версии зафиксированы: Kotlin 2.3.10, JVM 17,
`ai.koog:prompt-executor-deepseek-client:1.3.0-beta` (ядро 1.3.0).
Beta относится к клиенту DeepSeek; версия проверена по Maven Central.

## Подтверждённые ограничения

- Пробное подключение с Kotlin 2.0.21 (как в приложении) не компилируется:
  зависимости содержат Kotlin metadata 2.3.0, компилятор ожидает 2.0.0.
  Отключать проверку метаданных нельзя считать решением совместимости.
- В опубликованном `DeepSeekParams` нет типизированного `thinking`.
  Его необходимо передавать через `additionalProperties`; единое переключение
  reasoning всех провайдеров библиотека за нас полностью не решает.
- `DeepSeekClientSettings` позволяет задать собственный base URL и путь.
- Клиент содержит объединение reasoning и tool calls в одно assistant-сообщение.

## Результат 2026-10-14

На Kotlin 2.3.10: `BUILD SUCCESSFUL`, 6 тестов, 0 ошибок.
Проверены JSON-поля thinking enabled/disabled, отсутствие неявного thinking,
response_format, определения search_examples/read_example, объединение
reasoning с tool call и сериализация image_url. Изображение синтетическое;
это не проверка vision у модели и не выполнение инструментов.

Для запуска модульного клиента на JVM потребовался SLF4J runtime:
в тестах используется `slf4j-nop`, чтобы не записывать содержимое запросов.
Первый запуск без него завершился `NoClassDefFoundError: org/slf4j/LoggerFactory`.

## Границы проверки

Тесты через reflection вызывают сериализатор опубликованного клиента. Это
исследовательская проверка фактического JSON, а не рекомендуемый способ
интеграции: рабочий адаптер должен использовать публичный `execute`.

Пока не проверены: Android dex/runtime, транспорт и отмена запросов,
полный цикл инструментов, реальные ответы провайдера, миграция Kotlin/Compose.
Успех JVM-тестов сам по себе не означает готовность заменить клиент приложения.

## Рекомендуемая миграция

1. Отдельно обновить Kotlin и совместимые зависимости Android, проверить сборку,
   тесты, lint и запуск на эмуляторе.
2. За интерфейсом транспорта добавить Koog и контрактные тесты на mock HTTP:
   reasoning on/off, JSON-ответ, фото, search_examples/read_example,
   сохранение reasoning при tool calls, ошибки и отмена.
3. Сохранить наш лимит POST, checkpoint и фоновую генерацию. Не заменять их
   агентным циклом Koog в рамках миграции транспорта.
4. Переключать основной путь только после этих проверок.

Источники:
- https://docs.koog.ai/llm-providers/
- https://docs.koog.ai/prompts/llm-clients/
- https://docs.koog.ai/llm-parameters/
- https://repo.maven.apache.org/maven2/ai/koog/prompt-executor-deepseek-client/1.3.0-beta/

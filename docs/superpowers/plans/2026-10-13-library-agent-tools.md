# Library Agent Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Выполнять здесь самостоятельно по подтверждённому пользователем режиму.

**Goal:** Дать агенту локальные search_examples/read_example без обязательного поиска.
**Architecture:** Проверяемая сессия LibraryTools, ограниченный цикл LibraryAgentLoop
и существующий OpenAiClient. RequestFiles сохраняет счётчики/снимки; история и UI
показывают прочитанные версии, не меняя текущее решение.
**Tech Stack:** Kotlin, kotlinx.serialization/coroutines, OkHttp, Compose, JUnit.
**Spec:** docs/superpowers/specs/2026-10-13-library-agent-tools-design.md

## Global Constraints

- minSdk 26, без новых зависимостей, embeddings, MCP и обязательной подстановки.
- До 4 POST (fallback включён), 2 раундов, 8 calls/раунд, 5 прочитанных примеров.
- Результат чтения до 4000 Unicode-символов; инструменты до 12000; сообщения до 100000.
- Только разрешённые найденные UUID/версии; текст/источники, без автоматической отправки фото.
- Материалы role=tool; ключ/заголовки/сырые ответы не сохранять.
- Старые настройки/история совместимы, режим default false, отмена и restart без автоповторов.

## Review Focus

- Изменение версии/статуса между поиском и чтением не даёт устаревший доступ (Task 1).
- Неожиданный tool_calls на финальном POST не запускает пятый запрос (Task 2).
- Провайдер игнорирует tools/возвращает malformed call; ошибка отличается от пустой выдачи (Task 2).
- Поздняя отмена/checkpoint не записывает секрет и не вызывает скрытый retry (Task 3).
- Дополнения сохраняют источники своего ответа и прежние позиции чтения (Task 3).

### Task 1: Безопасные инструменты и снимки

**Files:** data/library/agent/LibraryTools.kt, LibraryAgentModels.kt;
app/src/test/java/com/smartcalc/app/data/library/agent/LibraryToolsTest.kt.
**Interfaces:** Consumes TaskSearch.entries/search, TaskTemplate, SourceCatalog.
Produces LibraryCatalog(sections,sources,error), LibraryTools(suspend catalog supplier),
execute(name:String,arguments:String):JsonObject, examples:List<LibraryExampleSnapshot>.

- [ ] Написать LibraryToolsTest: search возвращает UUID/версию; read до search запрещён,
  disabled/rejected/source disabled исключены; новая версия → stale; invalid arguments,
  no_match vs unavailable, budgets/Unicode, needs_image и отсутствие путей в JSON.
- [ ] Запустить :app:testDebugUnitTest --tests '*LibraryToolsTest'; Expected FAIL: новые типы отсутствуют.
- [ ] Реализовать DTO и сессию; повторять проверку доступа на каждом read.
- [ ] Повторить целевые тесты; Expected PASS, затем полный JVM-прогон.
- [ ] Коммит «Дать агенту безопасный поиск и чтение примеров библиотеки».

### Task 2: Ограниченный цикл и HTTP

**Files:** data/api/LibraryAgentLoop.kt, OpenAiClient.kt;
app/src/test/java/com/smartcalc/app/data/api/LibraryAgentLoopTest.kt, LibraryAgentHttpTest.kt.
**Interfaces:** Consumes Task 1 execute/examples; transport suspend (JsonObject)->JsonObject.
Produces LibraryAgentLoop.run(messages:JsonArray,forceSearch:Boolean):AgentCompletion,
AgentCompletion(content:String,report:LibraryAgentReport), typed assistant calls.
OpenAiClient.solve/followUp optional libraryTools/progress parameters, прежний payload.

- [ ] Написать loop-тесты search/read/final и final без tools (без запросов к поиску);
  force search, malformed/duplicate IDs, неизвестное имя, budgets/final call/cancellation.
- [ ] Запустить целевые тесты; Expected FAIL: новый цикл отсутствует.
- [ ] Реализовать цикл отдельно от parseSolution, счётчик перед POST, один JSON fallback.
- [ ] Написать HTTP-тесты тела tools и role=tool/tool_call_id, 422 fallback, unsupported
  tools, 429 без повтора, сохранение шаблона/формата и старого одиночного пути.
- [ ] Запустить loop/HTTP/прежние API-тесты; Expected PASS, затем весь JVM-прогон.
- [ ] Коммит «Добавить ограниченный цикл инструментов библиотеки в запросы модели».

### Task 3: Настройки, checkpoint, история и просмотр

**Files:** SettingsRepository.kt, SettingsScreen.kt, SmartCalcApplication.kt,
GenerationController.kt, RequestFiles.kt, HistoryStore.kt, SolutionScreen.kt,
ui/solution/LibraryExamples.kt, tests/request/RequestFilesTest.kt, tests/history/*.
**Interfaces:** Consumes LibraryAgentReport/LibraryTools; Produces optional agentReport
в Solution/FollowUp и save/readAgentReport(id,report) в RequestFiles.

- [ ] Написать тесты checkpoint ID isolation/clear/reload и сериализации старой истории,
  отчётов дополнений/снимков, редактирования ключа. Expected FAIL до реализации.
- [ ] Ввести default false переключатель; живой поставщик активной библиотеки;
  передать прогресс в checkpoint и результат в Solution/FollowUp.
- [ ] Встроить просмотр снимков в существующие блоки ответов, сохранить индекс чтения.
- [ ] Запустить весь JVM-набор, lintDebug, assembleDebug; Expected PASS, lint без ошибок.
- [ ] Обновить backlog/отчёт с границами текстовых tools и непроверенной GUI/API-приёмкой.
- [ ] Коммит «Подключить инструменты библиотеки к решению и сохранять прочитанные примеры».

После задач: один независимый обзор всей ветки, RED→GREEN для важных замечаний,
без публикации/мержа и без платных запросов в обычных проверках.

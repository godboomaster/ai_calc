# Декомпозиция SolutionViewModel и сборки зависимостей

> **Исторический план этапа, 2026-10-16.** Статус ветки и реализации ниже относится к этому этапу.
> Документ не является текущим поручением. Команды и разрешения ниже относятся только к исходному этапу.
> Текущие статусы — в [бэклоге](../../backlog.md), назначение документов — в [карте](../../README.md).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Отделить правила сеанса и операции библиотеки от текущего решения, сохранив поведение 1.6.4.
**Architecture:** `SolutionViewModel` остаётся координатором текущего решения и видимости помощника.
Чистые Kotlin-объекты `SolutionSession` и `ExampleWorkspace` содержат вынесенные правила.
`AppDependencies` собирает объекты уровня приложения; `LibraryImportComponent` создаёт один контроллер импорта.
**Tech Stack:** Kotlin, StateFlow, существующие файловые хранилища, Android ViewModel, JUnit/Compose tests.
**Spec:** [Задача бэклога](../../backlog.md#viewmodel-decomposition).

## Ограничения

- Нет новых функций, миграций, нового транспорта, DI-фреймворка или изменения `CalculatorModes`.
- Сохраняются пути файлов, схема JSON, выбранный шаблон, порядок публикации и настройки запроса.
- Публичные методы ViewModel остаются совместимыми делегатами; UI не получает нового владельца состояния.
- Файловый писатель `SessionStore` и фоновые контроллеры остаются на уровне процесса.
- API-ключ не читается; проверки используют локальные синтетические данные.
- Коммит, merge, push и выпуск не входят в этот этап.

## Владельцы состояния и время жизни

| Область | Владелец после переноса | Время жизни |
| --- | --- | --- |
| Файловый сеанс и очередь записи | Один `SessionStore` | Процесс, отдельный IO scope |
| Правила чтения/черновиков/непрочитанности | `SolutionSession`, вызываемый VM и фоновым запросом | Процесс; состояние только в SessionStore |
| Текущее решение, display, pending/error, события UI | `SolutionViewModel` / прежний `AssistantDisplay` | ViewModel |
| Выбранный шаблон и операции коллекций/поиска | `ExampleWorkspace` | ViewModel; файловые stores общие |
| Выполнение запроса и восстановление ответа | Прежние `GenerationController` / `RequestRunner` | Процесс, прежний application scope |
| Импорт, восстановление журнала и services callbacks | `LibraryImportComponent` / прежний `LibraryImportController` | Процесс, тот же application scope |
| Экранные черновики импорта | Прежний `LibraryImportViewModel` / SavedStateHandle | ViewModel; операции продолжаются в application scope |

## Review Focus

- Поздний scroll/collapse от уходящего экрана не меняет новую задачу или её ревизию.
- Ответ в фоне обновляет сохранённый сеанс, сохраняя прежние правила сброса позиции/свёрнутых блоков.
- Завершение запроса не снимает шаблон, выбранный пользователем после отправки.
- Создание Activity/VM не создаёт второй контроллер, recovery или файловую очередь записи.
- Ошибка сохранения библиотечного примера не показывает недолговечное решение и не запускает запрос повторно.

## Task 1: Правила сеанса

Files: `data/session/SolutionSession.kt`, `SolutionViewModel.kt`, `data/request/GenerationController.kt`,
`app/src/test/java/com/smartcalc/app/data/session/SolutionSessionTest.kt`.

Interfaces: `SolutionSession(SessionStore)`; `restoreFor(history): SessionState`, `rememberSolution(solution)`,
`updateFromRequest(solution, unread)`, `saveCalculator(value)`, `markViewed()`, операции чтения с явным текущим Solution.

- [x] Зафиксировать ожидания: stale id/revision, черновики при смене задачи, границы шага/блоков, background update.
- [x] Перенести существующие операции, включая фоновое обновление GenerationController, в один объект правил.
- [x] Проверить `SolutionSessionTest`, `SessionStoreTest`, `SessionRestorationTest`, `GenerationConnectionTest`.
  Command: `gradlew testDebugUnitTest --tests '*SolutionSessionTest' --tests '*Session*Test' --tests '*Generation*Test'`.
  Expected: все выбранные тесты проходят.

## Task 2: Библиотека и шаблоны

Files: `data/library/ExampleWorkspace.kt`, `SolutionViewModel.kt`, `app/src/test/java/com/smartcalc/app/data/library/ExampleWorkspaceTest.kt`.

Interfaces: `ExampleWorkspace(templates, library, history, sources, scope)`; прежние операции коллекций,
`armedTemplate`, `disarmIfSelected(id)`, `followUpTemplate(templateId)`, `resolveFound(entry): Solution`.

- [x] Зафиксировать ожидания выбора шаблона, импорта/экспорта коллекций и повторного открытия найденного решения.
- [x] Перенести реализацию; оставить делегаты ViewModel и прежний `WhileSubscribed(5000)` для поиска.
- [x] Проверить `ExampleWorkspaceTest`, существующие тесты поиска, TemplateStore и истории.
  Command: `gradlew testDebugUnitTest --tests '*ExampleWorkspaceTest' --tests '*Search*Test' --tests '*TemplateStoreTest' --tests '*HistoryStoreTest'`.
  Expected: все выбранные тесты проходят.

## Task 3: Сборка приложения и интеграция

Files: `AppDependencies.kt`, `SmartCalcApplication.kt`, `data/library/importer/LibraryImportComponent.kt`,
тесты компонента и Android-проверка времени жизни зависимостей.

- [x] Перенести сборку в AppDependencies с прежними путями, lazy-границами и scope; оставить совместимые getters приложения.
- [x] Выделить создание/однократное восстановление импорта, сохранив блокировку импорта активным решением.
- [x] Проверить однократный recovery, отмену/повтор и сохранность зависимостей после recreation Activity.
- [x] Полный JVM-набор, lint и сборка; целевые Android-проверки на изолированном пакете, без сетевых запросов.
  Command: `gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest -PisolatedDeviceCheck`;
  затем существующие `CalculatorModesDeviceTest` и новая проверка зависимостей.
  Expected: сборка/проверки успешны; пользовательский пакет не меняется.
- [x] Проверить diff отдельно по Review Focus; записать результаты и ограничения в задачу бэклога.

## Результат

Реализовано в `codex/viewmodel-decomposition` от `main` 1.6.4 (`1d067a7`), включено в состав 1.6.5.
`SolutionSession` объединяет правила чтения, черновиков и непрочитанности для VM и фонового запроса,
сохраняя один `SessionStore`. `ExampleWorkspace` владеет операциями коллекций, поиском и выбранным шаблоном
в пределах ViewModel. `AppDependencies` собирает зависимости процесса, `LibraryImportComponent` создаёт
один контроллер с однократным локальным recovery. UI и сервисы используют совместимые делегаты.
Владельцы состояния и шаги переноса: [таблица выше](#владельцы-состояния-и-время-жизни).

Проверено: 412 JVM-тестов (16 новых), `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`
(0 ошибок, 53 предупреждения в финальном прогоне); 9 Android-тестов на API 35 в изолированном пакете.
Проверки охватывают поздние события старого экрана, ревизии дополнений, выбор шаблона во время запроса,
сохранение найденного решения до показа, единственный recovery без повторной отправки/публикации,
recreation Activity с реальной прокруткой ответа и отдельные экземпляры ViewModel.
Существующие тесты отмены/повтора, восстановления запросов и калькуляторных переходов проходят.
Отдельное ревью не выявило регрессий рабочего кода; замечание к тестовым данным исправлено.

Визуально через Computer Use, только в окне API 35: история → ответ → прокрутка → скрытие,
тап по обычному калькулятору без клавиатуры, вкладки библиотеки и выбор шаблона.
Использованы синтетические данные, временные тестовые пакеты удалены; пользовательская установка не менялась.
Новых миграций и реальных запросов к провайдеру нет. API 26 и аварийное уничтожение процесса во время
платного запроса в этой итерации не проверялись; соответствующая приёмка импорта/агента остаётся открытой.

Код декомпозиции — `75a10d3`, включение в опубликованную 1.6.5 — `2b79b66`.
Записанные результаты относятся к проверкам этапа до выпуска, а не к повторной приёмке
скачанного релизного APK.

Из 16 новых JVM-тестов: 8 для сеанса, 7 для коллекций, 1 для сборки импорта.
Из 9 Android-тестов: 7 переходов калькулятора и 2 проверки зависимостей/чтения/времени жизни
ViewModel. Проверка чтения прокручивает длинный ответ через UI; арифметический снимок
формируется кнопками, чтобы не подменять состояние `rememberSaveable` вручную.
Пути/форматы файлов, scope и транспорт сохранены.

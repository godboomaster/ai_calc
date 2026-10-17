# Метаданные библиотеки: план реализации

> **Исторический план этапа, 2026-10-12.** Статус ветки и реализации ниже относится к этому этапу.
> Документ не является текущим поручением. Команды и разрешения ниже относятся только к исходному этапу.
> Текущие статусы — в [бэклоге](../../backlog.md), назначение документов — в [карте](../../README.md).

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Track completion below.

**Goal:** Добавить происхождение, проверку, версии и исключение примеров из поиска без потери библиотеки.
**Architecture:** Необязательные метаданные у библиотечных примеров, атомарное JSON-хранилище,
отдельные типы и правила в data/library. Текущий редактор и общий поиск используют эти поля.
**Tech Stack:** Kotlin, kotlinx.serialization, Compose, JUnit4; без новых зависимостей.
**Spec:** ../specs/2026-10-12-library-metadata-design.md

## Global Constraints

- API-ключ не читать; реальные API-запросы не выполнять.
- Не очищать данные приложения; не менять название «Калькулятор».
- Работать в codex/library-metadata, пользовательский Вариант 3.jpg не трогать.
- Экспорт — текущее содержимое без локальных статусов и архива версий.
- Не запускать несколько Gradle-сборок одновременно. SDK/JDK/Gradle находятся на D:.

## Review Focus

- Старая библиотека или ошибка диска: файлы не теряются, запись при ошибке чтения блокируется (task 1).
- Правка фотографии: архив сохраняет старый файл, текущая версия лишается прежней проверки (task 1).
- Чужой JSON: статусы/архив/локальные пути из метаданных не становятся доверенными (task 1).
- Выключенный пример уже открывался: сохранённый снимок не возвращает его в новый поиск (task 2).
- Неудачное сохранение: редактор остаётся открытым с черновиком, а не падает (task 2).

## Task 1: Хранилище и миграция

Files: data/library/LibraryMetadata.kt, data/template/TemplateStore.kt,
SmartCalcApplication.kt, data/library/LibraryMetadataStoreTest.kt.

Interfaces: `TemplateExample.library: LibraryMetadata? = null`;
`LibraryMetadata(id, version, origin, verification, enabled, previousVersions, schemaVersion)`;
`LibraryOrigin(kind: LibraryOriginKind, name: String? = null)`;
`LibraryRevision(version, condition, conditionImages, solution, solutionImages, verification)`.
`ExampleDraft` adds libraryId/expectedVersion/verification/enabled (nullable defaults).
`TemplateStore(root, kind = TEMPLATES)`, `loadError: StateFlow<String?>`,
`import(input, sourceName: String? = null)`. `ImportResult` adds unchanged: Int = 0.
`save` and `delete` accept `expectedSection: TaskTemplate? = null`; an existing library
section requires an equal expected snapshot. VM/editor pass the section loaded on entry.

- [x] Написать и запустить регрессии миграции, версий, импорта и отката; первый запуск отказал на отсутствующем API.
- [x] Реализовать типы и правила, отдельно от импорта изображений; сохранить совместимость существующих вызовов.
- [x] Считать фото предыдущих версий живыми ссылками при уборке. Удаление/экспорт/attach не переносят чужие архивы.
- [x] Проверить конфликт устаревшего раздела (включая отсутствующие в draft примеры) и разные разделы с одним ID внутри импорта.
- [x] Прогнать целевые тесты и проверить рабочий diff.

## Task 2: Редактор и поиск

Files: ui/template/TemplateEditScreen.kt, ui/template/TemplateTransfer.kt,
ui/search/LibraryScreen.kt, ui/search/SearchScreen.kt, SolutionViewModel.kt,
data/search/TaskSearch.kt; новые компоненты UI и тесты по необходимости.

Consumes: task 1 interfaces. SearchEntry adds optional library metadata for display.
Редактор хранит отдельный явный выбор проверки: не копировать старый VERIFIED в новый draft
как новое подтверждение. Любая последующая правка текста или фото очищает этот выбор.

- [x] Тестами закрепить фильтрацию выключенных/отклонённых и подавление библиотечных снимков истории.
- [x] Добавить источник/версию/статус, управление enabled и просмотр старых версий в существующий редактор.
- [x] Передавать имя импортируемого файла, объяснять экспорт без архива, показывать результат повторного импорта.
- [x] Обрабатывать ошибки хранения, не закрывая редактор с несохранёнными данными.
- [x] Обновить описание UI и бэклог, оставив исходники/страницы/историю→библиотеку открытыми.

## Task 3: Проверка и коммиты

- [x] `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=2` — успех.
- [x] Независимое ревью миграции, импорта, прав проверки и обхода фильтра через историю; исправить найденное.
- [x] `git diff --check`; коммит функции с тестами и отдельный коммит документации. Без push/релиза в этом пакете.

## Результат проверки

198 JVM-тестов в 29 наборах, 0 падений/ошибок/пропусков; lintDebug — 0 ошибок,
46 предупреждений; assembleDebug — успешная сборка. Локальный лог: build/library-metadata/verified.log.
Проверки на устройстве и реальные обращения к API не выполнялись.
Хранение и UI коммитятся вместе: миграция требует, чтобы редактор передавал ID и исходный снимок.
Коммит функции: `c6c9900`. Ревью закрыло конфликты устаревшего редактора, дубликаты ID
внутри импортируемого файла и выделение памяти при повреждённом номере версии.

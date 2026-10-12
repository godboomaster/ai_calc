# Импорт документов в библиотеку: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Track steps below.

**Goal:** Импортировать PDF, DOCX, фотографии и текст в отдельные редактируемые задачи с сохранённым оригиналом и точными ссылками на его фрагменты.
**Architecture:** Расширить существующую JSON-библиотеку, сохранив её публичный интерфейс; добавить отдельные источники, извлечение, распознавание, журнал импорта и восстанавливаемую публикацию. Черновики публикуются только после проверки пользователем; решение калькулятора и импорт имеют независимые контроллеры.
**Tech Stack:** Kotlin 2.0.21, Compose, kotlinx.serialization, coroutines, JUnit4, PdfRenderer, ограниченный JAXP DOM/SAX, OkHttp; Android minSdk 26 / compileSdk 35. Без Room, нового сервера и обновления стека.
**Spec:** [утверждённая схема](../specs/2026-10-12-library-document-import-design.md).

Дата: 2026-10-12. Схема подтверждена пользователем. План подтверждён пользователем; выполнение здесь завершило реализацию tasks 1–7. Доступные проверки task 8 пройдены; незакрытые пункты перечислены в отчёте.
Ветка: `codex/library-document-import`, основание: интегрированная и опубликованная `main` / `4dc4363`.

## Global Constraints

- Название «Калькулятор», вход ✦ → 9 → «База задач», независимые состояния расчёта и помощника сохраняются.
- Оригинал: 100 МиБ на файл; до 100 выбранных страниц за один импорт; превышение объясняется до обработки.
- DOCX: 8 МиБ XML на часть, 64 МиБ суммарно распакованных данных. Текст: 2 МиБ на единицу локального чтения.
- Растр: до 4 мегапикселей на декодированную страницу; последовательная обработка без загрузки всех страниц.
- Пакет: до 2 растровых страниц или 12 000 Unicode-символов текста, включая перенос незаконченной задачи.
- Запуск: до 10 внешних POST, включая fallback; тайм-аут одного POST — 120 секунд, активного запуска — 8 минут.
- Библиотека: до 500 опубликованных задач в новом сценарии; прежний больший набор не обрезается при миграции.
- Ключи установленного приложения не читать. Реальных API-запросов при разработке не выполнять; тестовый транспорт и локальный HTTP используют только синтетические данные.
- Пользовательский `Вариант 3.jpg` не трогать. Не удалять данные установленного приложения и не выпускать релиз.
- Подтверждение разбора не равно «Проверено мной». Неясные знаки/границы блокируют включение, пока замечание не разрешено.
- TXT/Markdown структурированный импорт доступен без сети. При неизвестной поддержке изображений vision-обработка выключена.
- DOCX имеет ссылки на блоки, PDF — реальные страницы от 1, фото — изображения; неизвестные ссылки из ответа модели отклоняются.
- Никакого автоматического RAG, embeddings, смены API решения или инструментального цикла в этом плане.
- Одновременно запускать только одну Gradle-сборку. SDK/JDK/кэш Gradle находятся на D:.

## Review Focus

- Удаление раздела после публикации и до отметки журнала: восстановление не возвращает удалённые задачи (task 6, постоянная квитанция).
- SHA256 совпал, но исходник удалён или повреждён: повторная загрузка восстанавливает байты явно, не считает отсутствующий файл пригодным (task 1).
- Край пакета внутри многостраничного решения: перенос входит в общий бюджет, задача остаётся целой либо требует проверки (tasks 4–5).
- Оригинал выключен после открытия задачи в истории: сохранённый снимок не возвращается в новый поиск, история остаётся читаемой (task 6).
- Процесс остановлен после получения ответа, но до checkpoint: никакого автоматического повторного POST (task 5).

## Файлы и общие решения

Новые области: `data/library/source/`, `data/library/extract/`, `data/library/importer/`, `ui/library/`.
Модели каждого направления находятся в своём `*Models.kt`; операции диска — в соответствующем store,
обработка жизненного цикла — в controller/service. Все пути ниже относительно корня проекта.
Существующий `DocumentReader` для решения одной задачи не заменяется новым импортом.

Для отдельного ответа добавить `TemplateExample.answer: String = ""` в конец конструктора,
а `ExampleDraft.answer: String? = null` — в конец: null сохраняет старое значение, пустая строка
явно очищает. Старые ключи `ExampleFingerprint` для пустого нового поля не меняются; непустой ответ
участвует в новом снимке. Экспорт переносит ответ самостоятельным полем/секцией, без локальных ссылок.

Локальное хранение: `filesDir/library/sources/<uuid>/original`, производные файлы рядом;
журнал: `filesDir/library/imports/<uuid>/job.json` и приватные файлы черновиков.
Оригиналы и журнал находятся в одной области резервного копирования с библиотекой;
ключи остаются в уже исключённом DataStore. Манифест/журнал с неизвестной схемой или ошибкой чтения
блокирует изменения, а не восстанавливается пустым списком.

Чтобы квитанции переживали последующее удаление задачи, библиотечный `templates.json` мигрирует
из массива в envelope `LibrarySnapshot(schemaVersion=1, sections, importReceipts)` атомарно.
Обычный TemplateStore для шаблонов сохраняет прежний массив. Публичный `items` остаётся списком разделов;
экспорт использует прежний переносимый bundle, а не внутренний envelope. Все библиотечные записи
после миграции сохраняют квитанции. Это необходимо для task 6 и не требует Room.

### Task 1: Исходники, ссылки версий и файловые фикстуры

**Files:**
- Create: `app/src/main/java/com/smartcalc/app/data/library/source/SourceModels.kt`, `LibrarySourceStore.kt`, `SourceInput.kt` в том же каталоге.
- Modify: `app/src/main/java/com/smartcalc/app/data/library/LibraryMetadata.kt`, `app/src/main/java/com/smartcalc/app/data/template/TemplateStore.kt` — ссылки текущей/прошлой версии, без публикации импорта.
- Create tests: `app/src/test/java/com/smartcalc/app/data/library/source/LibrarySourceStoreTest.kt`.
- Create: `scripts/library_fixtures.py`, `scripts/tests/test_library_fixtures.py`, `docs/library-quality/fixtures/manifest.json` и синтетические файлы в том же каталоге.
- Modify: `docs/library-quality/README.md`; test migration: `app/src/test/java/com/smartcalc/app/data/library/LibraryMetadataStoreTest.kt`.

**Interfaces:**
- `SourceFormat { PDF, DOCX, JPEG, PNG, TEXT, MARKDOWN }`; `SourceStatus { ACTIVE, DISABLED, DELETED }`.
- `LibrarySource(id, name, format, sizeBytes, sha256, relativePath, segmentCount: Int?, status)`; модели сериализуемы, id — UUID.
- `SourceSegmentRef(sourceId: String, segmentId: String, kind: SegmentKind, ordinal: Int, region: SourceRect? = null)`; `SegmentKind { PDF_PAGE, DOCUMENT_BLOCK, IMAGE }`, ordinal от 1.
- `SourceRect(left: Float, top: Float, right: Float, bottom: Float)` — нормированные 0..1, непустые границы; region отсутствует, если точность не подтверждена.
- `SourceInput(name: String, mime: String?, open: () -> InputStream)`; `SourceCopyResult(source: LibrarySource, duplicate: Boolean)`.
- `LibrarySourceStore(root: File)`: `state: StateFlow<SourceCatalog>`, `suspend fun copy(input: SourceInput): SourceCopyResult`, `suspend fun setEnabled(id: String, enabled: Boolean)`, `suspend fun deleteOriginal(id: String)`, `fun originalFile(id: String): File`, `suspend fun setSegmentCount(id: String, count: Int)`.
- `SourceCatalog(sources: List<LibrarySource>, error: String? = null)`; чтение оригинала проверяет canonical containment и состояние, не принимает пути снаружи.
- `LibraryMetadata.sourceRefs: List<SourceSegmentRef> = emptyList()` и тот же default в `LibraryRevision`; обычная правка сохраняет происхождение и архивирует старые ссылки.

- [x] Написать тест повторного копирования и чтения после перезапуска:

```kotlin
val root = temporaryFolder.newFolder()
val input = SourceInput("i01.txt", "text/plain") { ByteArrayInputStream("Условие".toByteArray()) }
val first = LibrarySourceStore(root).copy(input)
val second = LibrarySourceStore(root).copy(input)
assertEquals(first.source.id, second.source.id)
assertTrue(second.duplicate)
assertEquals("Условие", LibrarySourceStore(root).originalFile(first.source.id).readText())
```

- [x] Дополнить тестами предела 100 МиБ (поток без выделения такого массива), сбоя копирования/манифеста,
  одинакового имени с разным содержимым, path traversal, пропавшего/испорченного оригинала при повторе,
  блокировки каталога с ошибкой, ссылок прошлых версий и отсутствия новых полей в старых JSON.
- [x] Выполнить `:app:testDebugUnitTest --tests '*LibrarySourceStoreTest' --tests '*LibraryMetadataStoreTest'`;
  ожидается FAIL на отсутствующем API. Общие параметры команд приведены в task 8.
- [x] Реализовать потоковое копирование+SHA256 и sniff формата, временный файл/fsync/atomic move,
  атомарный каталог, откат и явное восстановление пропавших байтов при повторной загрузке.
  DOC/OLE, повреждённый ZIP и неподдержанные изображения отклонять с объяснением до распознавания.
- [x] Создать фикстуры I01–I14 по `benchmark.json`: TXT UTF-8/1251, двухстраничный PDF и его скан,
  DOCX с OMML/таблицей, два наклонённых задания, неясный знак, PDF на 6 страниц, ошибку автора,
  инструкцию внутри текста, защищённый PDF и смешанные границы. I09/I10 — сценарии повторов/сбоев,
  для слишком большого архива использовать потоковый генератор, а не огромный файл в Git.
  Manifest содержит sha256, формат, ожидаемые задачи/сегменты и причины неопределённости.
- [x] Применить навыки PDF/Documents при генерации этих артефактов; визуально проверить PDF/DOCX/растры,
  включая формулы и номера страниц. Проверить manifest и генератор через `python -m unittest discover -s scripts/tests -p test_library_fixtures.py -v`.
- [x] Повторить целевые JVM-тесты, ожидается PASS; проверить diff и закоммитить «Сохранять оригиналы и ссылки задач библиотеки».

### Task 2: Текст, DOCX, таблицы и OMML

**Files:**
- Create: `app/src/main/java/com/smartcalc/app/data/library/extract/ExtractionModels.kt`, `TextExtractor.kt`, `DocxExtractor.kt`, `OmmlText.kt`, `StructuredTaskParser.kt` в том же каталоге.
- Create tests: `app/src/test/java/com/smartcalc/app/data/library/extract/TextExtractorTest.kt`, `DocxExtractorTest.kt`, `StructuredTaskParserTest.kt` в том же каталоге.

**Interfaces:**
- `ExtractedSegment(ref: SourceSegmentRef, text: String, imagePaths: List<String>, warnings: List<ExtractionWarning>)`.
- `ExtractionWarning(code: String, detail: String, blocking: Boolean)`; `ExtractionResult(segments: List<ExtractedSegment>, totalSegments: Int, remainingOrdinals: List<Int>)`.
- `TextExtractor.extract(source: LibrarySource, input: InputStream): ExtractionResult`.
- `DocxExtractor.extract(source: LibrarySource, original: File, mediaRoot: File): ExtractionResult`.
- `OmmlText.convert(xml: String): OmmlResult(text: String, warnings: List<ExtractionWarning>)`.
- `StructuredTaskParser.parse(segments: List<ExtractedSegment>): StructuredParseResult`;
  `StructuredParseResult(tasks: List<ParsedTask>, unresolvedSegmentIds: List<String>)`,
  `ParsedTask(condition: String, solution: String, answer: String, refs: List<SourceSegmentRef>, warnings: List<ExtractionWarning>)`.

- [x] Тесты фиксируют UTF-8/1251, модуль E07, E01/E02 как разные задачи, сквозной порядок абзацев/таблиц,
  OMML-дробь E05, квадрат/индекс/корень; неизвестный оператор создаёт blocking warning:

```kotlin
val result = OmmlText.convert(ommlFractionE05) // строка фикстуры из тестового ресурса I05
assertTrue(result.text.contains("frac"))
assertTrue(result.text.contains("s"))
assertTrue(result.warnings.isEmpty())
assertTrue(OmmlText.convert(unsupportedOmml).warnings.any { it.blocking })
```

- [x] Добавить отказ при DTD/внешних сущностях, ZIP traversal/дублированной части, 8 МиБ XML,
  64 МиБ суммарно и 2 МиБ текста; неиспользованные части ZIP тоже расходуют общий лимит.
  Неподдержанный рисунок/объект и объединённая сложная таблица оставляют замечание, не исчезают.
- [x] Запустить `:app:testDebugUnitTest --tests '*library.extract.*'`: FAIL на отсутствующих типах.
- [x] Реализовать ограниченный ZIP и namespace-aware DOM/SAX parser без regex-разбора DOCX;
  OMML преобразовать в текст LaTeX для дроби/корня/степеней/индексов, остальное явно пометить.
  Блокам присваивать детерминированные ID по порядку исходного документа.
  StructuredTaskParser принимает только явные заголовки «Задача/Условие/Решение/Ответ»;
  неструктурированный текст возвращает unresolved для распознавания/ручной проверки.
- [x] Проверить PASS на реальных файлах I01/I02/I05/I11/I12/I14 и отсутствие автоматической авторской коррекции.
- [x] Commit: «Извлекать блоки, таблицы и формулы документов библиотеки».

### Task 3: PDF, фотографии и выбранные диапазоны

**Files:**
- Create: `app/src/main/java/com/smartcalc/app/data/library/extract/PdfExtractor.kt`, `ImageExtractor.kt`, `DocumentExtractor.kt`, `PageSelection.kt` в том же каталоге.
- Modify: `app/src/main/java/com/smartcalc/app/data/ImageUtils.kt` — выделить повторно используемую подготовку только при необходимости.
- Create tests: `app/src/test/java/com/smartcalc/app/data/library/extract/PageSelectionTest.kt`;
  `app/src/androidTest/java/com/smartcalc/app/data/library/extract/DocumentExtractorDeviceTest.kt` и фикстуры в `app/src/androidTest/assets/library-import/`.

**Interfaces:**
- `PageSelection(ordinals: List<Int>)`: непустой, уникальный, возрастающий список от 1, максимум 100.
- `DocumentExtractor`: `suspend fun inspect(source: LibrarySource): Int`,
  `suspend fun extract(source: LibrarySource, selection: PageSelection, onSegment: suspend (ExtractedSegment) -> Unit): ExtractionResult`.
- `PdfExtractor` и `ImageExtractor` реализуют подготовку сегмента с теми же `SourceSegmentRef` и лимитами task 2;
  `DocumentExtractor` маршрутизирует SourceFormat и сохраняет результат до вызова onSegment.

- [x] Начать с тестов диапазона и масштабирования; проверить 101 выбранную страницу как отказ и 100 как допустимый предел.
- [ ] На API 26/35 проверять I03/I04/I08: шестая страница действительно извлекается, ссылки 1..6,
  продолжение E06 сохраняет две страницы, изображение белое и читаемое, bitmap не больше 4_000_000 пикселей:

```kotlin
assertEquals(listOf(1, 2, 3, 4, 5, 6), result.segments.map { it.ref.ordinal })
assertEquals(SegmentKind.PDF_PAGE, result.segments.last().ref.kind)
assertTrue(decodedWidth.toLong() * decodedHeight <= 4_000_000L)
```

- [x] Запустить целевой JVM-тест и device-тест с новым API до реализации: FAIL.
- [x] Реализовать sequential PdfRenderer, проверку диапазона против pageCount, сохранение растров
  и освобождение bitmap/page/descriptor в finally; на API 35+ дополнительно текстовые contents,
  на API 26–34 — raster с теми же ссылками. Ни один путь не берёт первые четыре страницы неявно.
  Изображения подготовить с EXIF и bounded decode; порядок выбранных фотографий сохранять в job.
- [x] Проверить отказ для защищённого/повреждённого PDF, отмену между страницами, restart с checkpoint,
  EXIF-повороты и невозможность накопления всех bitmap; повтор локальной страницы не меняет segmentId.
- [x] Целевые тесты PASS; commit «Подготавливать выбранные страницы и фото для импорта задач».

### Task 4: Распознавание и возможности API

**Files:**
- Create: `app/src/main/java/com/smartcalc/app/data/library/importer/ImportModels.kt`, `RecognitionBatcher.kt` в том же каталоге.
- Create: `app/src/main/java/com/smartcalc/app/data/api/DocumentRecognizer.kt`, `DocumentRecognitionTransport.kt` в том же каталоге.
- Modify: `app/src/main/java/com/smartcalc/app/data/settings/SettingsRepository.kt`, `app/src/main/java/com/smartcalc/app/ui/settings/SettingsScreen.kt`.
- Create tests: `app/src/test/java/com/smartcalc/app/data/api/DocumentRecognizerTest.kt`, `DocumentRecognitionTransportTest.kt`;
  `app/src/test/java/com/smartcalc/app/data/library/importer/RecognitionBatcherTest.kt`.

**Interfaces:**
- `ApiSettings.supportsImages: Boolean = false`, сохраняется отдельно от systemPrompt/ключа.
- `ImportDraft(id: String, revision: Int, condition: String, solution: String, answer: String,
  conditionImages: List<String>, solutionImages: List<String>, refs: List<SourceSegmentRef>,
  warnings: List<ExtractionWarning>, excluded: Boolean)`; `ImportDraft` и ссылки сериализуемы.
- `RecognitionCarry(text: String, refs: List<SourceSegmentRef>)`; `RecognitionBatch(segments: List<ExtractedSegment>, carry: RecognitionCarry?)`.
- `RecognitionResult(drafts: List<ImportDraft>, carry: RecognitionCarry?, warnings: List<ExtractionWarning>)`.
- `RecognitionBatcher.next(segments: List<ExtractedSegment>, carry: RecognitionCarry?): RecognitionBatch?`:
  raster-пакеты до 2 страниц или текстовые до 12_000 символов, без смешения несовместимых режимов;
  полученный текстовый слой raster-страницы служит подсказкой в пределах тех же 12_000 символов.
- `RecognitionHttpResponse(code: Int, body: String)`; `RecognitionTransport.post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse` — suspend, один вызов = один POST.
- `DocumentRecognizer(transport: RecognitionTransport).recognize(settings: ApiSettings, batch: RecognitionBatch,
  beforePost: suspend () -> Unit, afterPost: suspend (RecognitionHttpResponse) -> Unit): RecognitionResult`:
  beforePost перед КАЖДЫМ POST, afterPost сохраняет ответ до разбора JSON, в том числе при fallback.
- `DocumentRecognizer.parseSaved(batch: RecognitionBatch, response: RecognitionHttpResponse): RecognitionResult`:
  только локальный разбор успешного checkpoint, без settings и сети. Сохранённый HTTP error требует явного process.

- [x] Написать тесты схемы извлечения, отделения ответа и продолжения, приоритета видимого warning для
  неясного знака I07, ссылки только из batch/carry, инструкции I12 как данных и сохранения ошибки E12.
- [x] Закрепить fallback и capability gate:

```kotlin
assertEquals(2, attemptsPersisted) // HTTP 422 + ответ без response_format
assertEquals(2, transport.calls.size)
assertEquals(0, textOnlyTransport.calls.size) // растровый batch, supportsImages=false
assertTrue(result.drafts.all { it.refs.all { ref -> ref.segmentId in allowedSegmentIds } })
```

- [x] Запустить целевые тесты `--tests '*DocumentRecognizerTest' --tests '*RecognitionBatcherTest'` до реализации: FAIL.
- [x] Реализовать независимый extract-only prompt, типизированный JSON и валидатор ответа;
  не применять systemPrompt решателя и `parseSolution`. Ответ транспортного уровня ограничить
  2 МиБ фактически прочитанных байтов; неподходящий/слишком большой ответ превращать в ошибку.
  HTTP 400/422 допускает один fallback, остальные ошибки не повторять автоматически.
  Модель возвращает ID сегментов и проверяемые области, а не локальные пути/URI;
  conditionImages/solutionImages строятся приложением из разрешённых медиа этих сегментов.
- [x] Реализовать OkHttp transport с тайм-аутом 120 секунд, `retryOnConnectionFailure(false)`,
  без автоматических redirect/followup POST и смены провайдера; отмена закрывает response и socket.
  Переиспользовать `apiEndpoint` для проверки URL; существующий OpenAiClient решения не переделывать.
  Добавить настройку «Модель поддерживает изображения» без вывода ключа в диагностику.
- [x] Тестировать транспорт на локальном ServerSocket, как в OpenAiClientCancellationTest;
  тесты границы 12_000/12_001, переноса, двух страниц, переполнения тела и отмены должны PASS.
- [x] Commit: «Распознавать задачи и проверять возможности модели при импорте».

### Task 5: Журнал и независимый контроллер

**Files:**
- Create: `app/src/main/java/com/smartcalc/app/data/library/importer/LibraryImportJournal.kt`, `LibraryImportController.kt`, `ImportDraftEditor.kt`, `ImportPublisher.kt` в том же каталоге.
- Modify: `app/src/main/java/com/smartcalc/app/data/library/importer/ImportModels.kt`.
- Create tests: `app/src/test/java/com/smartcalc/app/data/library/importer/LibraryImportControllerTest.kt`, `LibraryImportJournalTest.kt`, `ImportDraftEditorTest.kt`.

**Interfaces:**
- `ImportStatus { COPIED, EXTRACTING, RECOGNIZING, REVIEW_REQUIRED, PUBLISHING, READY, PAUSED, FAILED, INTERRUPTED, CANCELLED }`.
- `ImportJob(id, sourceIds: List<String>, selections: Map<String, PageSelection>, status, attempt: Long,
  postsUsed: Int, segments: List<ExtractedSegment>, processedSegmentIds: Set<String>,
  drafts: List<ImportDraft>, carry: RecognitionCarry?,
  activeBatch: RecognitionBatch?, inFlightPost: PostAttempt?, publication: PublicationIntent?,
  receipt: ImportReceipt?, error: String?)`.
- `PostAttempt(number: Int, segmentIds: List<String>, responseRelativePath: String?)`;
  HTTP response сохраняется отдельным атомарным файлом максимум 2 МиБ в каталоге job,
  без URL/ключа/заголовков. Наличие validated responseRelativePath означает завершённый checkpoint.
- `LibraryImportJournal(root: File)`: `jobs: StateFlow<ImportCatalog>`, `suspend fun save(job: ImportJob)`,
  `suspend fun recover()`; `ImportCatalog(jobs: List<ImportJob>, error: String? = null)`.
- `PublicationIntent(commitId: String, jobId: String, sectionId: String, sectionName: String,
  expectedSection: TaskTemplate?, items: List<PublicationItem>)`;
  `PublicationItem(exampleId: String, draftId: String, draftRevision: Int, condition: String, solution: String,
  answer: String, conditionImages: List<String>, solutionImages: List<String>, refs: List<SourceSegmentRef>)`.
- `ImportReceipt(commitId: String, intentSha256: String, sectionId: String, exampleIds: List<String>)`.
- `ImportPublisher`: `suspend fun publish(intent: PublicationIntent): ImportReceipt`, `fun receipt(commitId: String): ImportReceipt?`.
- `LibraryImportController` получает stores/extractor/recognizer/publisher/settings supplier/applicationScope/clock,
  `solutionActive: StateFlow<Boolean>`, hooks `startForeground: () -> Unit` / `stopForeground: () -> Unit`;
  `jobs: StateFlow<ImportCatalog>`; suspend `create(sourceIds: List<String>, selections: Map<String, PageSelection>): String`,
  `process(jobId: String)`, `cancel(jobId: String)`, `saveDraft(jobId: String, draft: ImportDraft, expectedRevision: Int)`,
  `publish(jobId: String, draftIds: List<String>, sectionId: String?, sectionName: String)`;
  process ставит запуск в applicationScope и возвращает управление; это новое явное действие
  с новым лимитом, не автоматический retry. Ограничение 100 страниц применяется суммарно ко всем selections job.
- `ImportDraftEditor.merge(drafts: List<ImportDraft>): ImportDraft`,
  `split(draft: ImportDraft, parts: List<ImportDraft>): List<ImportDraft>` — refs только исходного набора,
  перекрытие ссылок допустимо для разных задач на одной странице, сомнительная граница создаёт warning.

- [x] Написать тесты с FakeExtractor, FakeRecognizer, FakePublisher и управляемыми clock/сбоем записи:

```kotlin
assertEquals(ImportStatus.INTERRUPTED, recovered.status)
assertEquals(0, fakeTransport.callsAfterRestart)
assertEquals(10, fakeTransport.totalCalls) // fallback входит в лимит
assertEquals(ImportStatus.PAUSED, limitedJob.status)
assertEquals(originalSolution, generationState) // импорт не заменяет решение
```

- [x] Добавить red-проверки для checkpoint до/после POST, восстановления saved response без сети,
  остановки между страницами, late response отменённой attempt, отказа journal write, сохранения
  edits после Back/restart, merge/split ссылок и >12_000-символьного продолжения без скрытого обрезания.
- [x] Запустить `:app:testDebugUnitTest --tests '*library.import.*'` до реализации: FAIL.
- [x] Реализовать атомарный журнал и блокировку при ошибке чтения; сохранить activeBatch и попытку
  до сетевого вызова, response-файл и ссылку checkpoint до разбора, intention до publisher.
  Незаписанный исход POST на restart — INTERRUPTED; сохранённый успешный response передаётся
  в parseSaved. Ошибки API показываются без ключа, произвольный серверный error body в логи не попадает.
  После успешного локального разбора processedSegmentIds, drafts и carry обновляются одним snapshot;
  пустой результат на странице всё равно отмечает её обработанной. Повтор незавершённого checkpoint
  не прибавляет те же черновики дважды и не пропускает неполное продолжение.
  `saveDraft` сравнивает ревизию; блокирующие замечания разрешаются явной правкой/подтверждением,
  которое записывается в revision черновика, отдельно от математической verification.
- [x] Сериализовать выполнение одного job, сохранить ожидающие источники; между пакетами ждать
  отсутствия активного решения через поток generation, не отменяя его. Ожидание/проверка не держат
  foreground/wake lock; активный тайм-аут 8 минут отсчитывать только во время обработки.
- [x] Связать active work с hooks фонового выполнения: отказ старта/тайм-аут переводит импорт
  в сохраняемое прерывание; пауза останавливает foreground. Реальная Android-служба подключается в task 7,
  здесь используются fake hooks. Не использовать слот RequestRunner решения.
- [x] Проверить PASS и commit «Сохранять и восстанавливать независимый импорт документов».

### Task 6: Атомарная публикация задач и фильтрация источников

**Files:**
- Modify: `app/src/main/java/com/smartcalc/app/data/template/TemplateStore.kt`, `TemplateMarkdown.kt`, `ExampleFingerprint.kt` в том же каталоге.
- Modify: `app/src/main/java/com/smartcalc/app/data/library/LibraryMetadata.kt`, `app/src/main/java/com/smartcalc/app/data/search/TaskSearch.kt`, `FoundSolution.kt` в том же каталоге.
- Create: `app/src/main/java/com/smartcalc/app/data/library/importer/TemplateImportPublisher.kt`.
- Create tests: `app/src/test/java/com/smartcalc/app/data/library/importer/ImportPublicationTest.kt`, `app/src/test/java/com/smartcalc/app/data/search/LibrarySourcePolicyTest.kt`.
- Modify tests: `app/src/test/java/com/smartcalc/app/data/library/LibraryMetadataStoreTest.kt`, `app/src/test/java/com/smartcalc/app/data/template/TemplateStoreTest.kt`, `TemplateMarkdownTest.kt` в том же каталоге.

**Interfaces:**
- `TemplateStore.publishImport(intent: PublicationIntent): ImportReceipt` — suspend, LIBRARY only;
  `TemplateStore.importReceipt(commitId: String): ImportReceipt?` — чтение постоянной квитанции.
- `TemplateImportPublisher` реализует интерфейс task 5, проверяет source refs по текущему каталогу,
  источник не удалён, изображения доступны, затем вызывает TemplateStore.
- `TemplateExample.answer`, `ExampleDraft.answer` из общих решений; `LibraryRevision.answer: String = ""`.
  `LibraryOrigin` получает optional `importJobId/draftId`; обычная правка сохраняет происхождение.
- `TaskSearch.entries(base, templates, history, sources: SourceCatalog = SourceCatalog(emptyList()))`;
  типы первых трёх параметров прежние. Библиотечные refs допускают поиск только по известным ACTIVE sources;
  error каталога исключает только связанные примеры, старые без refs сохраняют прежнюю политику.
- `SearchEntry.answer: String = ""` в конце; FoundSolution предпочитает непустой отдельный ответ,
  иначе использует текущий разбор строки «Ответ». Индексируемый текст включает отдельный ответ.

- [x] Воспроизвести публикацию, повтор и пользовательское удаление до завершения журнала:

```kotlin
val first = publisher.publish(intent)
assertEquals(first, publisher.publish(intent))
assertEquals(1, library.items.value.single().examples.size)
library.delete(first.sectionId, library.items.value.single())
assertEquals(first, publisher.publish(intent))
assertTrue(library.items.value.isEmpty()) // постоянная квитанция запрещает воскрешение
```

- [x] Дополнить тестами остановки до/после atomic move, error диска и отсутствия фото, конфликта
  expectedSection/изменённого intent с прежним commitId, capacity 500/501, новых детерминированных UUID,
  UNVERIFIED по умолчанию, копий фото независимо от originals/черновиков, сохранения квитанций при save/delete.
  Проверить миграцию массива без потерь и blocking будущей envelope schema.
- [x] Red-прогон `--tests '*ImportPublicationTest' --tests '*LibrarySourcePolicyTest' --tests '*TemplateStoreTest'`.
- [x] Реализовать внутренний envelope и idempotent квитанцию в том же atomic snapshot, что и задачи.
  ID новых примеров вычислять `UUID.nameUUIDFromBytes("library-import:<jobId>:<draftId>".toByteArray())`.
  Фото копировать в библиотечный каталог; совпадение квитанции проверять по canonical intent hash,
  включающему SHA256 байтов фотографий. При повторе с квитанцией не проверять уже удалённый раздел
  и не создавать его снова. Ошибка до snapshot откатывает только файлы текущей операции.
- [x] Расширить answer сериализацию/редактор/архив/экспорт; импорт внешнего bundle не принимает
  локальные provenance/receipts. Пустой answer сохраняет старый fingerprint и ID найденного решения.
  Markdown поддерживает optional «### Ответ»; JSON/ZIP roundtrip сохраняет answer без metadata.
- [x] Добавить фильтр источников до дедупликации и не допустить обхода через библиотечную историю.
  Ошибка/удаление исходника сохраняет читаемость текста и собственных фото старой истории.
- [x] Проверить PASS, старые регрессии поиска/истории и commit «Публиковать импортированные задачи без дублей и потери ответов».

### Task 7: Файлы, проверка разбора и просмотр оригинала

**Files:**
- Create: `app/src/main/java/com/smartcalc/app/ui/library/LibraryFilesScreen.kt`, `ImportSelectionScreen.kt`, `ImportReviewScreen.kt`, `SourceViewerScreen.kt`, `LibraryDocumentTransfer.kt`, `LibraryImportViewModel.kt` в том же каталоге.
- Create: `app/src/main/java/com/smartcalc/app/LibraryImportService.kt`.
- Modify: `app/src/main/java/com/smartcalc/app/ui/search/LibraryScreen.kt`, `app/src/main/java/com/smartcalc/app/ui/AppRoot.kt`, `app/src/main/java/com/smartcalc/app/SolutionViewModel.kt`.
- Modify: `app/src/main/java/com/smartcalc/app/ui/template/ExampleEditState.kt`, `TemplateEditScreen.kt`, `LibraryExampleControls.kt` в том же каталоге.
- Modify: `app/src/main/java/com/smartcalc/app/ui/camera/CameraScreen.kt`, `app/src/main/java/com/smartcalc/app/SmartCalcApplication.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/file_paths.xml`.
- Create tests: `app/src/test/java/com/smartcalc/app/ui/library/ImportReviewStateTest.kt`; modify `app/src/test/java/com/smartcalc/app/ui/template/ExampleEditStateTest.kt`.

**Interfaces:**
- Routes: `library/files`, `library/import`, `library/review/{jobId}`, `library/source/{sourceId}?ordinal={ordinal}`;
  UUID источника и номер выбранного фрагмента, не filesystem path. Навигация использует URI encoding.
- `LibraryImportViewModel` предоставляет state каталогов и suspend-команды controller/source store;
  экран хранит выбранную вкладку/ожидающее действие/camera target через SavedStateHandle,
  долгие операции — applicationScope controller. Состояние решения не используется как UI импорта.
- CameraScreen получает `onImportInput: (suspend (SourceInput) -> Unit)? = null`; callback
  копирует исходный camera-файл до finally/delete. Выбор галереи также передаёт исходный поток
  и имя через SourceInput, до сжатия. Обычный режим решения сохраняет прежний default.
- `ImportReviewState.selectedDraftIds: Set<String>` и `canPublish: Boolean` вычисляются по excluded,
  пустым условиям/решениям, unresolved warnings и доступности ссылок; confirm не ставит verification.

- [x] Проверить состояния review и редакторского ответа:

```kotlin
assertFalse(stateWithUnresolvedSign.canPublish)
assertTrue(stateAfterExplicitCorrection.canPublish)
assertEquals(LibraryVerification.UNVERIFIED, editedAnswerState.verification)
assertEquals(savedAnswer, unchangedEditor.toDraft().answer)
```

- [x] Red-тесты ревизий, merge/split/exclusion и сохранения answer/sourceRefs в обычном редакторе.
- [x] Подключить stores/controller один раз в SmartCalcApplication, VM searchEntries — combine с каталогом sources;
  добавить вкладки «Задачи»/«Файлы», отдельное меню импорта документа и существующий импорт готовой базы.
  Выбор нескольких фото сохраняет порядок и открывает проверку диапазона; камера возвращает оригинал
  в импорт, а не отправляет его на решение. URI сразу копируется до потери разрешения.
- [x] Подключить отдельную START_NOT_STICKY-службу dataSync с собственным notification ID,
  сообщением «Обрабатывается документ», обработкой startForeground отказа и onTimeout к hooks task 5.
  Controller applicationScope не принадлежит Activity; pausing/ready/review прекращают foreground/wake lock.
- [x] Экран запуска показывает формат, диапазон, API-профиль/поддержку изображений и предел 10 POST;
  даёт локальную/ручную подготовку без vision. Ошибка не меняет прежний draft/решение.
- [x] Экран review: condition/solution/answer, ссылки, предупреждения, исключение, merge/split с явным
  распределением refs, «Добавить выбранные»; Back сохраняет revision. В существующем редакторе
  доступны отдельный answer, оригинал и ссылки прошлых версий, изменение снимает проверку.
- [x] PDF viewer открывает настоящий номер страницы; фото — конкретное изображение; DOCX/TXT — blockId.
  Во внешнее приложение копировать только выбранный оригинал в cache/shared/<uuid>/,
  выдавать FileProvider URI + временный read grant; не расширять provider на корень private files.
  Удаление/выключение исходника требует явного действия и обновляет поиск; missing/deleted явно объясняется.
- [x] Проверить PASS, assembleDebug и commit «Добавить проверку импорта и просмотр источников в библиотеке».

### Task 8: Приёмка импорта, документация и завершение

**Files:**
- Modify: `docs/library-quality/README.md`, `docs/library-ui.md`, `docs/backlog.md`, `README.md`.
- Create: `docs/library-quality/import-check-2026-10-12.md`; update completion в этом плане.

- [x] Для каждого предыдущего task применять цикл red → implementation → green; коммитить только
  его файлы, включая необходимые тесно связанные UI/миграции. Не включать пользовательскую фотографию.
- [x] Команды JVM: `.\gradlew.bat :app:testDebugUnitTest <фильтры> --offline --console=plain --max-workers=2`.
  PASS — exit 0 и XML без failures/errors; red записывать с реальным отсутствующим API/дефектом.
- [x] Полный итог: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=2`;
  `python -m unittest discover -s scripts/tests -v`; `git diff --check`. Подсчитать свежие XML-результаты.
- [ ] Установить тестовую сборку на API 26 и 35 без очистки пользовательского пакета; для основного пакета
  сначала проверить подпись, использовать install -r только совместимого APK. Если API 26 недоступен,
  не объявлять эту часть приёмки выполненной. Device-команда: `:app:connectedDebugAndroidTest` с теми же Gradle параметрами.
- [ ] Пройти I01–I14 синтетическими данными: сохранить источник, разбор, правки, границы и ответ;
  открыть каждую ссылку, повторить источник и публикацию, отключить/удалить original, перезапустить процесс.
  Сетевые ошибки/паузы/разрывы воспроизводить поддельным transport в тестах, без ключей установленного приложения.
- [ ] Проверить Activity recreation при выборе файла/камере и review, «Домой» во время импорта,
  независимость решения, запуск dataSync-service и отказ Android, границу пакета и остаток большого документа.
- [x] В отчёте перечислить фактические commit/device/API, результаты I01–I14, счётчики POST,
  ограничения распознавания и непроведённые проверки. Поддельный API не доказывает качество внешней модели.
- [x] Провести итоговое ревью миграции/квитанций, источников/истории, лимитов/POST/отмены и UI;
  исправить подтверждённые замечания и повторить затронутые проверки.
- [x] Обновить backlog только по фактически выполненным критериям: оригиналы/импорт/проверка готовы,
  автоматический RAG и инструменты агента остаются открытыми. Commit: «Зафиксировать проверку импорта библиотеки».
- [ ] После зелёных проверок интегрировать ветку выбранным пользователем способом; push/релиз
  не выполнять без соответствующего разрешения. Следующий самостоятельный план — поиск примеров агентом.

## Проверка плана перед выполнением

Подготовительный коммит после проверки плана: «Спланировать импорт документов в библиотеку».
Включить только этот план и обновлённый статус утверждения в
`docs/superpowers/specs/2026-10-12-library-document-import-design.md`.

- Источники/версии/миграции — tasks 1 и 6; форматы/страницы/ограничения — tasks 2–4.
- Проверка границ и исправление — tasks 5 и 7; фон/бюджет/восстановление — task 5.
- Атомарное добавление, квитанции и фильтр источников — task 6; приёмка I01–I14 — task 8.
- DTO и имена соседних интерфейсов определены выше; task 5 тестируется с FakePublisher до реализации task 6.
- Продуктовый код ещё не изменён; следующий шаг — пользовательская проверка этого плана и выбор выполнения.

## Фактическое завершение

Реализация и доступные проверки выполнены. [Отчёт](../../library-quality/import-check-2026-10-12.md) содержит результаты I01–I14, решения и ограничения.
API 26, реальный OCR, полный lifecycle камеры/службы и интеграция ветки остаются открытыми.
Изменения сохранены в feature-ветке; main, push и релиз не выполнялись.

# Review fixes implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation, followed by an independent read-only review.

**Goal:** Fix all confirmed findings from the 2026-10-11 review, then run automated tests and user-facing checks in the existing Android emulator.

**Architecture:** Keep the existing Android/Compose application. Make template publication atomic and image deletion reference-aware; align calculator parsing and submission; bound document/image loading and protect credentials. Review the changes before committing them.

**Tech Stack:** Kotlin, Android API 26+, Compose, coroutines, kotlinx.serialization, JUnit, Gradle.

**Spec:** The user's confirmed review findings in this chat, with README.md as the existing behavior contract.

## Global constraints
- Preserve existing calculator, background requests, history and session behavior.
- Do not implement deferred RAG/library features or change release infrastructure.
- Preserve user files and emulator app data; do not use real API calls for regression tests.
- Work in the authorized existing checkout. Keep changes uncommitted during review; the subsequent user request authorizes logical local commits. Do not push or change Git configuration.
- Implementation is authorized by the user; execute continuously without a separate plan approval gate.

## Review focus
- Shared imported photos survive editing/deleting/reimporting one referring template.
- A failed metadata write preserves the previous library and its image files.
- Operators/backspace after an arithmetic error never submit an AI request.
- Large input and EXIF handling operate before full-size allocation.
- HTTPS enforcement and backup exclusions preserve ordinary secure API operation.

## Tasks

### 1. Template integrity (P1, P2, duplicate-image P3)
Files: data/template/TemplateStore.kt; data/template/TemplateStoreTest.kt.
- [x] Add and run regressions for shared images on delete/edit/reimport, failed publication, duplicate IDs, unsupported version.
- [x] Atomically publish JSON before removing globally unreferenced owned images; clean staged files on failed writes.
- [x] Normalize duplicate IDs before image localization; reject unsupported versions before writing.
- [x] Run TemplateStoreTest, retaining the existing export/import examples.

### 2. Calculator and math text (P2, P3)
Files: ui/calculator/ExpressionParser.kt, CalculatorScreen.kt, pure calculator input policy helper; their tests; ui/math/MathMarkup.kt and tests.
- [x] Add/run scientific-notation roundtrip, invalid-result key handling, and adjacent modulus-line regressions.
- [x] Parse the format produced by the calculator; prevent keypad-origin error text from entering AI submission.
- [x] Require an unambiguous table shape for separator-free tables.
- [x] Run the calculator/math suites and verify keypad behavior in the emulator.

### 3. Credentials and transport (P2)
Files: AndroidManifest.xml, res/xml backup rules, data/api/OpenAiClient.kt and URL validation tests.
- [x] Verify/restrict authenticated remote requests to HTTPS before network I/O; preserve HTTPS custom hosts.
- [x] Exclude credential DataStore from legacy backup and modern cloud/device transfer.
- [x] Test URL rejection without contacting an API and check packaged backup resources.

### 4. Documents and photographs (P2)
Files: data/ImageUtils.kt, data/DocumentReader.kt, input-size/image geometry helpers and tests.
- [x] Add/run bounded-stream, image sampling and orientation regressions.
- [x] Decode sampled bitmaps, apply all EXIF orientations, and release intermediate bitmaps.
- [x] Limit text and ZIP-entry reads before allocating complete input; retain existing small-document behavior.
- [x] Run unit tests and Android image checks where supported.

### 5. Camera availability (P2)
Files: ui/camera/CameraScreen.kt and focused camera-selection policy tests if extracted.
- [x] Handle no back camera/provider errors with a visible retry/fallback state and gallery access.
- [x] Preserve cancellation and unbind the camera when leaving the screen.
- [x] Build and check camera/gallery navigation in the emulator.

### 6. Final verification and review
- [x] Run testDebugUnitTest, assembleDebug, lintDebug, and git diff --check.
- [x] Install the debug APK on the already running emulator without clearing app data.
- [x] Test calculation, errors, IME drafts, templates and available media paths using computer-use.
- [x] Obtain fresh-context read-only review of the complete diff; fix actionable findings and rerun affected checks.
- [x] Report results and any device/runtime limitations honestly.

## Execution record
- Baseline: d55fe63; tracked tree clean; only user-owned untracked Вариант 3.jpg.
- Ruling: keep work uncommitted in the user-authorized checkout, use this document as the progress ledger; no worktree/commit workflow needed for the requested local fixes.
- Ruling: use synthetic fixtures only for emulator tests; never submit real API credentials/tasks.
- Task 1: complete. TemplateStoreTest: 15 tests, initial 6 expected failures, then all passed. Global references retained across delete/edit/reimport; staging failure leaves metadata/photos unchanged; duplicate IDs and unsupported versions covered.
- Task 2: RED confirmed: 4 failures for scientific notation, keypad error continuation/submission, adjacent modulus equations. Extracted existing input rules into a pure helper before fixing behavior.
- Task 2: complete. 19 calculator/math tests passed after the fixes.
- Task 3: RED confirmed: remote HTTP and embedded credentials accepted. Ruling: preserve HTTP only for localhost/127.0.0.1/::1, without DNS-based allowlisting; remote endpoints require HTTPS. Backup excludes the complete settings DataStore directory, including staging files.
- Task 3: complete. API tests including real local-socket cancellation passed; resources compiled.
- Task 4: RED confirmed: 4 JVM failures (oversized TXT/DOCX and image decode planning). On emulator API35, both actual bitmap/EXIF tests failed at orientation 2 before implementation. Android tests use AndroidJUnitRunner 1.6.2 in a separate `.review` installation, with no API key and no user data.
- Task 4: complete. Full JVM suite passed; Android bitmap tests passed (2 tests, file and URI, each orientations 1-8).
- Task 5: complete. Camera policy regression: 4 tests, initial 3 expected failures, then all passed. Emulator API35: provider/bind completed, preview appeared, gallery picker opened, returning restored preview, shutter captured the synthetic emulator scene and returned to the calculator.
- Task 6: UI checks use a separate `com.smartcalc.app.review` installation via an ignored Gradle init script. The existing `com.smartcalc.app` installation and its data are preserved.
- Emulator UI: `1 / 0 =` followed by `+ 1 =` produced local `1`; IME entry `1000000000*1000000` produced `1E15`, then keypad `/ 2 =` produced `500000000000000`.
- Emulator UI: imported two synthetic templates sharing one photo, deleted A, opened B and observed its retained photo. Device file/metadata inspection also confirmed the remaining reference and file.
- Independent read-only analyst review identified a remaining absolute-value case (`|x| = |y|`). Added a failing regression, rejected ambiguous separator-free relation rows, and obtained reviewer acceptance of the follow-up.
- Full regression run exposed intermittent omission of `updated` by dynamic serialization defaults. Enabled `encodeDefaults` for template persistence/export; the unchanged reload-equality test passed afterward. Reviewer accepted this bounded follow-up.
- Packaged APK inspected with aapt2: legacy backup, cloud backup and device-transfer rules all exclude `file/datastore/`; merged manifest references those resources and network security configuration.
- Additional UI regressions confirmed by the reviewer: missing API key showed a misleading model-response message; reopening an editor after a failed photo discarded the saved draft. Added failing regressions (1 error-message test and 2 draft tests), then fixed local configuration exception handling and draft precedence.
- Review rulings: deferred RAG/library features remain outside scope. No remote paid API calls or physical-device backup/restore are required for these fixes. Real absent/front-only camera hardware is unavailable in this emulator; selection/failure/cancellation are covered by unit tests, available-camera navigation by UI.
- Final automated verification: `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` passed with the `.review` init script; 137 JVM tests, 0 failures/errors/skips; lint 0 errors, 46 warnings (dependency recommendations, existing UI/resource warnings and the new runner dependency/catalog recommendation). `git diff --check` passed. Android instrumentation passed both tests again on the final APK; log: `build/review-fixes/android-tests.log`.
- Final UI verification: capturing the emulator scene without a key displays `API-ключ не задан. Откройте настройки и введите ключ.`; `Review draft 123` survives closing/reopening over a failed photo. A synthetic history entry preserves both equations `|x| = |y|` and `|2x| = |y+1|` visually. A failed follow-up retains the edited `Corrected follow-up` after closing/reopening.
- Final independent read-only analyst assessment: no Critical, Important or Minor findings remain after the reviewed follow-ups. Conditions of the assessment (full test/build/lint success and repeated UI scenarios) are now satisfied.
- Reviewer-declined behavior ruling: manual Settings connection-test error display is unchanged and outside these fixes; the sanitized error-message contract tested here is the main solution flow and request journal. No repository infrastructure or deferred features were added.
- Review completion: all confirmed original findings and additional confirmed review/UI findings are fixed. Changes were left uncommitted for the user's decision; user-owned `Вариант 3.jpg` and the original emulator application data are unchanged.
- Commit follow-up: the user authorized splitting and committing the reviewed changes. Group by template integrity, calculator input, math markup, API configuration/security, document limits, image processing, camera availability and this verification record. Exclude `Вариант 3.jpg` explicitly.

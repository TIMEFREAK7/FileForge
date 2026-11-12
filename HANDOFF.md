# FileForge — handoff brief

Written 12 Nov 2026 after a full read-through audit. This file is everything needed to start work without the audit conversation.

## 1. Where things are

- **Repository:** `TIMEFREAK7/FileForge` (public, MIT).
- **`main` is empty:** only `LICENSE` and a Node-template `.gitignore`.
- **All code is on `ccr-92ca601b-ogu37r`:** 34 commits, last one 31 Oct 2026, CI green on every push. There is no PR, no README and no release.
- **Read these first, on that branch:** `docs/spec.md` (the contract), then `docs/phase0.md` to `docs/phase3.md` (what is built, what was measured, known gaps).

FileForge is a Kotlin, Jetpack Compose Android app (minSdk 36) that converts and edits images and PDFs fully offline, for family use. It has no network permission by design.

| Module | Role |
| --- | --- |
| `core` | Plain Kotlin: formats, presets, limits, file names, sniffing, page geometry. Unit-tested. |
| `engine-image` | Image decode and encode, plus `ParserCalls`, the shared binder client for the parser processes. |
| `engine-pdf` | PDFBox-Android and Android's `PdfRenderer`: `PdfOps` (merge, split, compress, render), `PdfEdit` (markup, forms, redaction). |
| `engine-media` | LAME MP3 spike only. Not wired into the app. |
| `jobs` | WorkManager queue: `JobQueue`, `ConvertWorker`, `JobStore`, `AppSettings`, `PdfPasswords`. |
| `app` | One activity, Compose UI under `ui/`. |

## 2. Rules that must not be broken

These come from the spec's Security table. A change that breaks one is wrong even if it works.

- **S1, S2:** no network permission, no broad storage permission. CI checks the merged manifest.
- **S3:** `MainActivity` is the only exported app component.
- **S4:** no user file is parsed in the main process. PDFs and JPG/PNG/WebP are parsed in the isolated process; HEIC decoding and all encoding run in `:media`.
- **S7:** incoming file names are display text only, never paths.
- **S9, S10:** no backup, no file names in release logs.
- **S11:** every dependency is pinned. Changing one means refreshing the lockfiles (`./gradlew resolveAndLockAll --write-locks`) and `gradle/verification-metadata.xml`, or CI fails.
- **S13:** redacted content must be gone from the output bytes.
- **S14:** PDF passwords live in memory only.
- **S16:** no signing material in the repository.
- Originals are never modified or deleted.

## 3. How to work

- **Build and checks** (from `.github/workflows/ci.yml`): Java 21, NDK `28.2.13676358`, CMake `3.31.6`.
  - `./gradlew assembleRelease assembleDebug assembleDebugAndroidTest :tools:permission-canary:assembleRelease`
  - `./gradlew testDebugUnitTest :core:test :app:lintDebug :jobs:lintDebug :engine-image:lintDebug :engine-pdf:lintDebug`
  - `python3 scripts/check_manifest.py <release apk> --allow-exported scripts/exported-allowlist.txt`
- **No device here.** Anything that needs a phone (the in-app self-tests, motion, TalkBack, other readers) is run by the owner on a OnePlus 13, who sends screenshots. Say exactly what to tap and what to send back.
- **The audit was a code read, not a run.** Before fixing a bug below, reproduce it: a unit test where the logic is pure, otherwise a new self-test line in `app/ui/SelfTest.kt`.
- **Small commits, one concern each,** with CI green before moving on. Never skip or disable a test to get green.
- **Keep the style:** short plain-language comments, user-facing errors in plain words, British spelling in UI text.
- **Update the phase doc** when a gap in its "Not done yet" list is closed.

## 4. Scope

### In scope

Everything in sections 5 to 8: the bug fixes, the unfinished gates, the missing v1 pieces, the UI consistency pass, and the toolchain upgrade.

### Out of scope unless the owner says otherwise

- v2 features: PDF to Word, Word to PDF (`engine-python`, `engine-office`).
- Editing existing PDF text, certificate signatures, any network feature.
- Pinch zoom and draggable signatures in the editor (listed as known gaps; nice to have, not required for v1).
- Tablet and foldable layouts.

## 5. Bugs to fix first

Confidence: **Certain** means the code path leaves no doubt. **Likely** means a strong inference that still needs reproducing.

| # | Problem | Where | Fix direction | Confidence |
| --- | --- | --- | --- | --- |
| B1 | Redaction can leave hidden content in the file. Kept pages copy the source page's whole resources dictionary, which many PDFs (scans especially) share across all pages, so a redacted page's original image is still written out. The S13 self-test misses it because its test PDF has per-page resources. | `PdfEdit.contentOnly`, `PdfEdit.redact` | First add a test PDF whose pages share one resources dictionary and make S13 fail. Then either rasterise any kept page that shares an XObject with a redacted page, or prune each kept page's resources to the names its content stream uses. | Likely |
| B2 | Rotation, the dark-mode switch or a font-size change recreates the activity and loses everything: the editor closes with all marks gone, the organiser closes, shared files vanish before the donut opens. | `AndroidManifest.xml` (no `configChanges`), `AppRoot.kt`, `MainActivity.kt` (state held in `remember` and a plain field) | See question Q3. | Certain |
| B3 | Long-pressing a Recents row near a screen edge starts with the finger already on a segment, because the ring centre is clamped inward but hover uses the clamped centre. Lifting converts (to PDF by default on the left edge); holding 600 ms opens that segment's options. | `Donut.kt` (`slotAt`, the clamp of `c`), `AppRoot.kt` (`onEnd`, which also tests the hole against the unclamped centre) | Arm hover only after the finger has moved a set distance from the press point or has been inside the hole. Use the same centre in both files. | Likely |
| B4 | Cancelling a job mid-file records a bogus error, "The converter stopped unexpectedly (JobCancellationException)". The same path unbinds the parser process whenever a thumbnail render is cancelled by scrolling. | `ParserCalls.call`: `catch (e: Exception)` swallows `CancellationException` | Rethrow `CancellationException` before the general catch. | Certain |
| B5 | Back closes the editor and the organiser with no confirmation, discarding all edits. | `PdfEditorScreen.kt`, `PdfPagesScreen.kt` (`BackHandler(onBack = onClose)`) | Confirm when there are unsaved changes. | Certain |
| B6 | Compress skips images with a soft mask but not ones with an explicit mask or a colour-key mask, so their transparency is flattened into the JPEG, probably as black. | `PdfOps.recompress` | Also return null when the image has `/Mask`. Add a self-test image with one. | Likely |
| B7 | A share intent's `EXTRA_STREAM` is not limited to `content://`, so another app could point FileForge at its own private files by `file://` path. Low risk (names are UUIDs), one-line fix. | `MainActivity.handle` | Drop any URI whose scheme is not `content`. | Certain |

Smaller defects, same milestone if cheap:

- `JobStore.save` and `load` are `@Synchronized` on an instance, but every caller makes a new `JobStore`, so the lock protects nothing.
- `ParserCalls.bind` has no timeout; a service that never connects hangs the job.
- `ImageConverter.withWhiteBackground` never recycles the flattened bitmap.
- `PdfOps.open` reads `doc.numberOfPages` after `doc.close()` when building the over-limit message.
- The organiser can add more than `Limits.MAX_FILES_PER_JOB` PDFs, which then throws in `JobQueue.add`; the donut silently drops files past 200.
- `AppRoot` recomputes `recentOutputs(jobs)` and reads `settings.donutOrder` on every drag frame; `JobQueue.observe` re-parses every `job.json` on every progress tick.
- `Limits.MAX_IMAGE_PIXELS` of 100 MP means over 1 GB of transient bitmaps across processes; reconsider for slower phones.

## 6. Missing against the spec

- **Phase 2 gate still open:** the Full self-test (1,000 mutated PDFs), airplane mode for PDF tools, a real scanned PDF and a real protected PDF.
- **Phase 3 gate still open:** every on-phone check in `docs/phase3.md` is "Not yet tried".
- **Phase 4 (video and audio) not started.** `engine-media` holds only a LAME wrapper that encodes a whole buffer in memory and divides by `channels` unchecked; it needs a streaming rewrite. No Media3, no video or audio formats, no share filter for them.
- **Tests:** `core` has 14 unit tests; `engine-pdf` has one (`FdRandomAccess`). Nothing covers `JobStore` JSON round-trips, `JobQueue.opFor`, or the organiser's `move` and `splitBefore`. S8, S13 and S14 are "Automated" in the spec but exist only as a manual debug self-test. The S4 instrumented test and the 20-files-per-format corpus do not exist. CI compiles the instrumented tests but never runs them.
- **Screens and options:** no Folders screen, no page-range option in the options sheet, no "More" segment.
- **Notifications:** no completion notification; the progress notification has no tap action and no Cancel.
- **Licences:** the in-app list omits PDFBox-Android (Apache 2.0, ships in the APK).
- **Visual design:** liquid-glass surfaces, shape morphing, shared-element transitions and the feature icon family are not built.
- **Repository:** no README, no release, no install notes for family members. `docs/phase0.md` still describes deleted spike code and has a broken table row.
- **CI:** actions are pinned by tag, not commit SHA; there is no scheduled run, so the OSV scan only fires on a push.

## 7. UI and UX consistency pass

- **Theme:** `surfaceContainerLow`, `surfaceContainerHighest`, `outlineVariant`, `errorContainer` and others are unset in `Theme.kt`, so bottom sheets, dividers and switch tracks fall back to Material's purple-grey. Set every role. No typography or shapes are defined either.
- **Headers:** five title sizes across six screens. Pick one top-bar pattern and use it everywhere, with one way to close or go back.
- **Naming:** tab "Jobs", empty state "No conversions yet", notification "Converting files" (also for merges). "Edit PDF pages", "Edit pages", "Edited PDF" and "Sign & mark up", "Marked-up PDF" overlap, and both features save `name_edited.pdf`.
- **Dead donut:** an unsupported file, or images mixed with PDFs, shows every segment grey with no explanation. The TalkBack list has a message; the donut needs one.
- **Feedback gap:** after a format is chosen nothing appears until all inputs are copied. Show progress at once.
- **Editor:**
  - Colour swatches are 40 dp boxes using a raw tap detector: below the spec's 48 dp and not selectable with TalkBack.
  - Undo removes the last mark even on another page and does not cover form values. There is no redo.
  - The text preview uses Android's sans-serif while the saved file uses Helvetica.
- **Forms:** fields are labelled with internal names (`f1_01[0]`); use the field's tooltip name when present. Choice fields render every option as a chip, which fails for long lists.
- **Password prompt:** no show-password toggle.
- **Recents and Jobs:** removing a job removes its files from Recents; Jobs cards have no Open or Share; Recents rows have no thumbnail or format icon.
- **Insets:** the editor and organiser use status and navigation bar padding, other screens use safe-drawing padding. Use one.
- **Donut labels** can outgrow their segments at large font sizes.
- **Accessibility switch:** the donut-or-list choice is read once and matches Switch Access by a name substring.
- **Strings:** all UI text is hard-coded English; `strings.xml` holds only the app name. See Q6.

## 8. Toolchain

Versions checked against Google Maven, Maven Central and gradle.org on 12 Nov 2026.

| Item | In the repo | Latest published |
| --- | --- | --- |
| Android Gradle Plugin | 8.13.2 | 9.5.1 |
| Gradle | 8.14.3 | 9.9.1 |
| Compose BOM | 2026.06.01 | 2026.09.00 |
| Material 3 | 1.4 | 1.5.0-rc01 (not stable) |
| Kotlin | 2.4.21 | 2.4.21 |
| WorkManager | 2.12.0 | 2.12.0 |
| PDFBox-Android | 2.0.27.0 | 2.0.27.0 |

- Gradle 9, AGP 9.x, `compileSdk` 37 and the newer BOM probably have to move together (the spec says Compose 1.12 needs AGP 9.1 and compileSdk 37). Not verified by building.
- Keep the `Motion` shortcut in `Theme.kt` until Material 3 1.5 is stable.
- PDFBox-Android has no newer release and looks dormant. The isolated process is what makes that acceptable; do not move PDF parsing out of it.

## 9. Roadmap

Each milestone ends with CI green. Do them in order unless the owner's answers change it.

| # | Milestone | Done when |
| --- | --- | --- |
| M0 | Land the project | The code is on `main` through a PR (Q1), with a README: what the app is, how to build, how to install, where the spec is. `docs/phase0.md` fixed. |
| M1 | Correctness | B1 to B7 fixed, each with a test that failed before the fix. The smaller defects in section 5 fixed where cheap. Owner re-runs Quick self-tests on the phone. |
| M2 | Close the open gates | Owner runs the Phase 2 and Phase 3 phone checklists; results recorded in the phase docs; anything they find is fixed. Unit tests added for `JobStore`, `opFor`, `move`, `splitBefore`. CI gets a weekly schedule and SHA-pinned actions. |
| M3 | UI consistency | Section 7 done. Owner checks light and dark mode, large font, and TalkBack on the phone. |
| M4 | Missing v1 pieces, not media | Completion notification with a tap action, licences list, page-range option, dead-donut message, Folders screen (Q8 may change this). |
| M5 | Toolchain upgrade | Section 8 applied, lockfiles and verification metadata refreshed, R8 `profile` build re-checked on the phone including the PDF features. |
| M6 | Phase 4: media | Per the spec's Phase 4 gate: Media3 Transformer in `:media`, streaming MP3, limits and timeouts for video. |
| M7 | Visual design | Liquid-glass surfaces, shape morphing, shared-element transitions, icon family (Q5). |
| Release | v1 | Phases 0 to 4 passed, plus S12 (MobSF scan) and S15 (offline key, published SHA-256). |

## 10. Questions for the owner before starting

Ask these first. Each has a default so work can proceed on the parts that do not depend on the answer.

| # | Question | Default if unanswered |
| --- | --- | --- |
| Q1 | How should the code reach `main`: one PR from `ccr-92ca601b-ogu37r`, and which branch should new work go on? | Do not open a PR without a yes. Work on a new branch cut from `ccr-92ca601b-ogu37r`. |
| Q2 | Can you run the on-phone self-tests and checklists on the OnePlus 13 and send screenshots, and how quickly? | Assume yes, within a day; batch phone requests per milestone. |
| Q3 | For rotation (B2): lock the app to portrait, let the activity handle configuration changes itself, or save full state so it also survives Android killing the app? | Handle configuration changes in the manifest and save only the open screen and shared files; full editor state restore is not attempted. |
| Q4 | For redaction (B1): is it acceptable that extra pages lose selectable text when they share content with a hidden page? | Yes: correctness of hiding beats keeping text selectable. |
| Q5 | Is the liquid-glass look required for v1, or can v1 ship with the current Material look once it is consistent? | Deferred to M7, after media. |
| Q6 | Does the app need Hindi or Marathi UI text, or is English enough? | English only; move strings to `strings.xml` so translation is possible later. |
| Q7 | Upgrade the toolchain now (M5 before M1) or after the fixes? | After, so fixes are not mixed with a build migration. |
| Q8 | Keep the first-run folder picker, or save to `Documents/FileForge` automatically with no setup, with the picker as an option in Settings? | Keep the picker; raise the alternative again at M4. |
| Q9 | Which phones will run this, beyond the OnePlus 13: any with less memory, any on Android 17? | Assume slower phones exist; lower `MAX_IMAGE_PIXELS` only with the owner's agreement. |
| Q10 | Is Phase 4 (video and audio) still wanted for v1, or should v1 ship as images and PDFs? | Still wanted, as M6. |
| Q11 | Does the release keystore exist yet, with its offline backup (S15)? | Assume not; remind at the Release step. Never handle the key. |

## 11. What was not checked

- Nothing was built or run; the findings come from reading the source.
- `app/ui/SelfTest.kt` was skimmed, not reviewed line by line.
- The vendored LAME source, the lockfiles and `gradle/verification-metadata.xml` were not reviewed.
- Dependency versions were checked for existence, not for compatibility with each other.

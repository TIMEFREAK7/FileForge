# Phase 1 — core and images

Scope from [spec.md](spec.md#build-phases-and-testing-gates): the donut, share entry, jobs queue, Settings and all image conversions.

## What is built

| Part | Where |
| --- | --- |
| Converter contract: formats, presets, targets, safe file names, format sniffing, limits | `core` (plain Kotlin, unit-tested) |
| Image engine: decode, resize, encode to JPG / PNG / WebP / HEIC, location stripped unless kept, transparent → white for JPG | `engine-image/ImageConverter` |
| Parser processes: JPG/PNG/WebP decoded in the isolated process and encoded in `:media` (the encoder stalls ~5 s in an isolated process), HEIC wholly in `:media`, per-file timeout, crash recovery | `engine-image/ImageEngineService`, `ImageEngineClient` |
| Jobs: inputs copied in on queueing, WorkManager worker, progress notification, cancel, retry of failed files, cleanup at start | `jobs` |
| UI: first-run folder choice, Home with Pick files and Recents, donut (drag and tap modes), options sheet, Jobs, Settings, share and Open-with entry | `app/ui` |
| Accessibility: TalkBack or Switch Access swaps the donut for a plain list; 48 dp targets; system font size | `Donut.kt`, `Sheets.kt` |
| Adaptive launcher icon with themed (monochrome) layer | `app/src/main/res` |
| Self-tests for the phone (debug builds): Settings → Run self-tests | `app/ui/SelfTest.kt` |

## Security checks now in CI

S1, S2, S3, S9 (manifest), S11 (sha256 verification, lockfiles, OSV scan), S16 (gitleaks), plus unit tests (S5 limits, S7 names) and lint. The release APK is about 3 MB.

## How to check the Phase 1 gate on the phone

Install the debug APK over the old one, then:

1. **First run:** choose a folder (suggested: make `Converted` inside Documents).
2. **Self-tests:** Settings → Run self-tests → **Quick** (about a minute). Screenshot the list. Then **Full** (the 1,000-file fuzz pass per format, 10–20 minutes, keep the screen on) and screenshot the end.
3. **Donut, in app:** convert one photo with Pick files, then long-press its entry in Recents, slide to a format and lift. Hold on a segment for the options sheet. Lift outside the ring to cancel.
4. **Share entry:** in Gallery, select several photos (include a HEIC one if you have it), Share → FileForge, tap a format.
5. **Swiped-away job:** share 20+ photos, tap a format, and immediately swipe FileForge away from recent apps. Reopen after a minute: the job in Jobs should be done.
6. **Metadata:** convert a photo taken with location on. Open the result's details in Gallery: no location. Repeat with "Keep photo details" on in the options sheet: location present.
7. **Motion:** Developer options → Profile HWUI rendering → On screen as bars. Open and drag the donut: the bars should stay well under the green line. Then turn on Developer options → Remove animations and check the donut and sheet appear and disappear instantly.
8. **Airplane mode:** repeat steps 3 and 4 with airplane mode on.
9. **TalkBack:** turn TalkBack on, long-press a Recents entry: a list of formats should appear instead of the donut.

Send screenshots of steps 2 and 7, and note anything that looked wrong in the others.

## Results — OnePlus 13, Oct 27, 2026

| Gate item | Result |
| --- | --- |
| Self-tests, Full | 44 of 44 passed in 1 min 39 s |
| 100 mixed photos convert with no crash | 100 of 100, 9 s converting |
| Fuzz pass, 1,000 mutated files per format | JPG, PNG, WebP, HEIC: zero app crashes, zero converter crashes, zero timeouts |
| HEIC → JPG keeps orientation | Passed (self-test) |
| Location absent unless kept on purpose | Passed for JPG, WebP and PNG (self-test) |
| Originals unchanged (hash) | Passed for every format |
| S7, S8 | Passed |
| Real photos converted from the app | Camera photo → HEIC and screenshot → JPG appear in Recents |
| Speed, 12 MP JPG → JPG | ~210 ms (decode 80 ms isolated + encode 130 ms in `:media`); was 5.1 s with encoding in the isolated process |
| Swiped-away job, motion bars, airplane mode, TalkBack | Not yet checked |

## Not done yet (honest gaps)

- **Liquid-glass look, shape morphing and shared-element transitions** from the spec's motion system are not built. Phase 1 has the palette, the springy donut and the standard Material 3 components. The full look is better added once the screens are stable, so it isn't reworked twice.
- **Folders screen** (browse a granted folder, long-press for the donut) is not built; Pick files covers choosing files for now.
- **S4's instrumented test** (fail if a parser class loads in the main process) is not written; S4 is enforced by structure (all decoding goes through `ImageEngineClient`) and code review.
- **A hung isolated process** cannot be killed by the app (different user id); after a timeout it is unbound and left for Android to reclaim. Hung files are counted in the fuzz pass.
- **HEIC output keeps no metadata**: Android's ExifInterface cannot write HEIF.
- The release build (R8-shrunk) has not been run on a phone; the debug APK is what you test.

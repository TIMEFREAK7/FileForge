# FileForge — v1 Spec

Oct 26, 2026 · @Aditya

A Kotlin Android app that converts and edits files fully offline, with no network permission at all, for family use, on Android 16 and newer. v1 covers images, PDF tools, signing and media; PDF↔Word follow in v2 after a feasibility spike.

## Brand identity and visual design

**App name:** FileForge\
**Tagline:** Your Files, Your Way.

### Brand direction

Design FileForge as a polished, modern Android utility with a fluid, tactile interface inspired by the visual language of modern Android OEM skins and the expressive motion and adaptive surfaces associated with modern Android design. Use these as inspiration rather than copying proprietary screens or assets. The result should feel calm, premium, responsive, and easy to use for family members.

- **Visual character:** fluid motion, soft depth, clean geometry, natural transitions, generous spacing, readable typography, and restrained translucent/glass-like surfaces where they improve hierarchy.
- **Liquid surfaces:** sheets, the donut and floating controls read as liquid glass: soft blur and gentle refraction of what sits behind them, continuous rounded curves with no hard corners, light that shifts subtly with depth, and edges that catch a faint highlight. Shapes morph smoothly between states (a segment swelling under the finger, a button flowing into a sheet) and settle with spring physics, not fixed-time easing. Text on glass always sits on a solid enough tint to keep full contrast.
- **Palette:** deep teal/navy foundations, cyan-to-teal accents, emerald green, a restrained lime highlight, and pale blue/off-white surfaces. Keep contrast and accessibility strong; avoid excessive glow, gradients, or decorative effects.
- **Icon concept:** a pale document with a folded corner, encircled by two smooth, interlocking conversion arrows, on a deep teal-blue rounded-square background. The icon must remain legible at small launcher sizes and work on light and dark surfaces.
- **Arrow refinement:** refine the blue/cyan arrow from the current concept. It should look intentional and elegant, not bulky, awkward, or overly saturated blue. Prefer a smooth cyan-to-teal gradient, balanced ribbon thickness, rounded transitions, clean arrowheads, and a natural flowing curve. Make its direction and silhouette immediately readable. Balance it visually with the green/lime return arrow so neither arrow dominates. Avoid the blue arrow looking like a separate, pasted-on shape. Preserve the central document's clarity and keep the arrows from obscuring too much of it.
- **Wordmark:** "FileForge", with "File" in dark slate/navy and "Forge" transitioning from cyan/teal to emerald green. Use a clean, confident sans-serif wordmark.
- **Tagline treatment:** "Your Files, Your Way." Use smaller, calm slate text beneath the wordmark.
- **Motion language:** use fluid, natural transitions driven by springs and subtle feedback. The radial conversion menu should feel tactile and responsive, with a clear selection state and restrained haptic feedback. Respect Android animation and reduced-motion/accessibility preferences. Motion must never delay file operations or obscure status and error messages.
- **Feature iconography:** use a consistent family of simple, rounded, legible icons for Image, PDF, Video, Audio, Edit, Sign, and More. Use color accents sparingly and consistently.

### Implementation guidance

Treat the branding image/concept as visual direction, not as a finished UI to reproduce pixel-for-pixel. Build the icon and interface from clean, scalable assets and native Jetpack Compose components where practical. Keep the UI performant on supported family devices, maintain adequate touch targets and contrast, and ensure the app remains fully functional offline. Branding and animation must not introduce network permissions, remote assets, analytics, or other online dependencies.

- **Motion system:** build all motion on Android's own expressive motion system in Compose (Material 3 Expressive), not on hand-tuned curves:
  - the theme's expressive motion scheme supplies every spring, so speed and bounce stay consistent across the app;
  - shape morphing (Material shapes over androidx.graphics.shapes) for the donut segments, buttons and sheet transitions;
  - shared-element transitions from a file in Recents or Folders into the donut, options sheet and editor;
  - predictive back, so swiping back previews the screen underneath and the liquid sheet follows the finger.

  Glass blur uses Compose's built-in blur (RenderEffect), kept to a few surfaces on screen at once so scrolling stays smooth on family phones slower than the OnePlus 13. With "Remove animations" on, every transition jumps straight to its end state and haptics remain.
- **Icon deliverables:** an adaptive icon as vector layers (foreground and background, key shapes inside the central safe zone), plus a flat single-colour monochrome layer for Android's themed icons. Gradients and gloss do not carry into the monochrome layer, so the two-arrow silhouette must read on its own.
- **No OEM names or marks:** no OEM skin names or marks appear in the app, its icon, its store or GitHub assets (this spec included, since the repository is public), or the brand asset prompt.

### Brand asset prompt

Use this prompt when creating or refining the FileForge app icon and brand presentation:

> Create a polished, modern identity for **FileForge — "Your Files, Your Way."** Use a modern Android-inspired visual language: a fluid, liquid-glass feel with soft blur and gentle refraction, continuous rounded curves with no hard corners, light that shifts subtly with depth, faint highlights catching the edges, shapes that look as if they could flow and settle like liquid, clean geometry, and a calm teal-blue-green palette. The primary icon is a deep teal/navy rounded square containing a pale document with a folded corner, wrapped by two smooth, flowing conversion arrows. Refine the blue arrow: replace the awkward, overly strong blue appearance with a smoother cyan-to-teal ribbon, balanced thickness, rounded transitions, and a clean, well-shaped arrowhead. It should curve naturally around the document and visually interlock with the green-to-lime return arrow. Keep both arrows balanced, readable, and integrated as one coherent symbol; do not let the blue arrow dominate or obscure the document. Preserve the green arrow's fluid shape. Present the "FileForge" wordmark with "File" in dark slate and "Forge" in cyan-to-emerald, with the exact tagline "Your Files, Your Way." The overall result should be premium, simple, approachable, and legible at small sizes. Avoid gloss, excessive glow, clutter, tiny details, harsh saturated blue, and extra text.

## Scope

v1 is everything that converts well offline with Android's own APIs or small libraries. Anything needing a full office engine waits.

| Feature | Release | Notes |
| --- | --- | --- |
| HEIC, JPG, PNG, WebP ↔ each other | v1 | Resize, quality, strip location metadata by default |
| Images → PDF | v1 | One PDF from many images, page order set by the user |
| PDF merge, split, compress | v1 | Compress = re-encode embedded images at a chosen quality |
| PDF page tools | v1 | Rotate, delete, reorder, extract pages |
| PDF → images | v1 | PNG or JPG per page at a chosen DPI |
| Sign PDF (drawn signature) | v1 | Signature image stamped on the page; not a cryptographic signature |
| Annotate PDF | v1 | Pen, highlight, text box, shapes. Latin-script text is written as PDF text; Devanagari and other complex scripts are laid out by Android's text engine and stamped as an image, because PDFBox does no script shaping (that text is not selectable) |
| Fill PDF forms | v1 | Existing AcroForm fields only; XFA forms are not supported and show a plain error. Values in complex scripts are flattened as a stamped image, so that field is no longer editable |
| Redact PDF | v1 | Redacted pages are rasterised so hidden text is removed, not just covered. The output is always a full save (never incremental), with document info, XMP metadata, attachments and bookmarks stripped |
| Password-protected PDFs | v1 | The password is asked for each job. Outputs stay protected with the same password by default; the options sheet offers "Remove password" |
| Video → MP4 (H.264/HEVC), resize, trim | v1 | Hardware encoders only |
| Video → audio, audio → MP3, M4A (AAC) or WAV | v1 | MP3 through a bundled LAME encoder; M4A through the platform encoder |
| PDF → Word (DOCX) | v2 | pdf2docx via Chaquopy, after the Phase 0 spike passes |
| Word → PDF | v2 | LibreOffice engine, built from Collabora Office's Android source |
| Editing existing PDF text | Out | Use PDF → Word → PDF when needed |
| Cryptographic (certificate) signatures | Out | Needs certificates and a trust chain; not worth it for family use |
| Any network feature | Out | The app ships without the INTERNET permission |

## Donut behaviour

The donut is the same everywhere; only the way in differs. Android does not let an app add itself to another app's long-press menu, so the share sheet is the bridge from other apps.

**Entry points**

1. **In the app:** long-press a file in Recents or a chosen folder. The donut opens under the finger.
2. **From any app:** long-press the file there, tap Share, pick the app. The donut opens at screen centre with the shared files.
3. **Open with:** a file opened with the app goes straight to the donut.

**How it works**

- Shows only formats that fit the selected files. A mixed selection shows what all of them can become.
- Up to 8 segments in fixed positions for each input type, so a slide that worked yesterday works today. Defaults put the common targets first and can be rearranged in Settings; usage never reorders them. "More" always sits in the same spot and opens the full list.
- Slide to a segment and lift = convert with that format's default preset. Haptic tick on entering a segment.
- Hold on a segment = an options sheet (quality, size, DPI, page range, output name) before converting.
- Lift outside the ring = cancel.
- Output goes to one folder the user picks once at first run (default Documents/Converted). Writing next to the original is not reliable on Android without broad storage access, which this app will not request.
- Files arriving through Share or Open with are copied into the app cache as soon as they arrive, because the read grant that comes with them can expire before a background job runs.
- Long jobs (video, big PDFs) run in the background with a progress notification. Results show in Recents with Open and Share.

**Accessibility**

- Every segment has a TalkBack label. With TalkBack or Switch Access on, the donut is replaced by a plain list of the same targets.
- Segments are at least 48 dp; text follows the system font size.

## Screens

Nine screens, one activity, built in Jetpack Compose.

| Screen | What it does |
| --- | --- |
| Home | Recents, a Pick files button (images and PDFs, which open the donut: merge, compress and PDF → images are donut segments), and an Edit PDF pages button for the organiser |
| Folders | Browse folders the user granted (via the system folder picker); long-press opens the donut |
| Donut overlay | The radial menu described above |
| Options sheet | Per-format preset: quality, size, DPI, page range, file name, remove password |
| Jobs | Queue with progress, cancel, retry, and the error in plain words when one fails |
| PDF organiser | Page thumbnails to merge (Add PDF), split before selected pages, extract, reorder, rotate, delete |
| PDF editor | Viewer with pen, highlight, text box, shapes, signature, form fill, redact |
| Signatures | Draw, save and delete signatures; stored only inside the app |
| Settings | Output folder, default presets, donut segment order, keep-or-strip metadata, open-source licences |

## Libraries

Platform APIs first; a library only where Android has nothing. Confidence is from memory, not yet checked against each project's current release: verify versions and licences in Phase 0.

| Engine | Library | Licence | Used for | Confidence |
| --- | --- | --- | --- | --- |
| UI and motion | Jetpack Compose 1.11 (BOM 2026.06.01), Material 3 1.4 | Apache 2.0 | Springs, predictive back, blur | Certain. Compose 1.12 needs AGP 9.1 and compileSdk 37, so it waits for that upgrade. Material 3 1.4 keeps its expressive motion scheme internal, so the same springs are defined in the app until 1.5 is stable |
| Images | Android ImageDecoder, Bitmap.compress, androidx HeifWriter, androidx ExifInterface | Apache 2.0 | HEIC decode, JPG/PNG/WebP encode, HEIC encode on phones with a hardware HEVC encoder, metadata | Likely |
| PDF render | Android PdfRenderer | Platform | Thumbnails, viewer, PDF → images | Certain |
| PDF edit | PDFBox-Android | Apache 2.0 | Merge, split, page tools, compress, stamps, annotations, AcroForm forms. No complex-script shaping and no XFA | Likely |
| Complex-script text | Android StaticLayout drawn to a bitmap | Platform | Devanagari and other scripts PDFBox cannot shape, stamped onto the page | Likely |
| Video, audio | AndroidX Media3 Transformer | Apache 2.0 | Transcode to MP4/M4A, trim, resize, extract audio | Likely |
| WAV out | Own code over MediaCodec PCM | — | WAV writer, about 50 lines | Certain |
| Background jobs | AndroidX WorkManager | Apache 2.0 | Queue, retry, foreground service (type `mediaProcessing`) for long jobs | Certain |
| PDF → Word (v2) | Chaquopy + pdf2docx (needs PyMuPDF) | MIT + AGPL | Layout-aware DOCX output | Guessing: PyMuPDF on Android unproven |
| Word → PDF (v2) | LibreOffice core from Collabora Office Android | MPL 2.0 | DOCX/ODT → PDF | Likely, hard build |
| MP3 out | LAME, built with the Android NDK | LGPL 2.0, shipped as its own .so | MP3 encoding from decoded PCM | Likely |

The repository is public from day one under the MIT licence, and your own code stays MIT. Once v2 bundles PyMuPDF (AGPL), the APK as a whole must follow AGPL terms, which a public repo already meets. LAME's LGPL is met by shipping it as a separate .so.

## Architecture

One app module plus one module per engine, so v2's Python and LibreOffice engines drop in without touching the UI.

- **app:** Compose UI, donut, share and Open-with entry points.
- **jobs:** WorkManager queue; each job = input URIs, target format, preset, output folder.
- **engine-image, engine-pdf, engine-media:** v1 converters. engine-python and engine-office arrive in v2.
- **Converter contract:** each engine answers two questions: which targets fit this MIME type, and convert(inputs, target, preset) → output, where inputs is a list so merge and images → PDF fit the same contract. The donut builds its segments from the first answer.
- **Parser processes:** files are opened outside the main app process, so a bad file can crash a worker but never the app.
  - **Isolated process** (isolatedProcess, no storage or network rights): PDFBox, PdfRenderer and JPG/PNG/WebP decoding (the parsing). Decoded pixels go to the `:media` process for encoding through SharedMemory: the Phase 1 speed check showed the platform image encoder stalls about 5 s per image in an isolated process (decode 40 ms, encode 5,100 ms), against about 90 ms elsewhere. It receives file descriptors from the main process and writes to an output descriptor. Page bitmaps for the viewer and thumbnails come back through SharedMemory, since a binder transaction is capped at about 1 MB.
  - **Media process** (a separate, non-isolated `:media` process): Media3 Transformer, HEIC decoding, and all image encoding (only of pixels already decoded in the isolated process, never of user files). Phase 0 confirmed hardware MediaCodec kills an isolated process. HEIC decoding may also go through the platform's hardware decoder, so HEIC input is routed here too.
- **Storage:** inputs through the Storage Access Framework only. Every input (picked, shared or opened with) is copied into app storage when the job is queued, under a numbered name, never its own (S7); a share grant can expire before the job runs. An input's copy is deleted as soon as it converts; copies of failed inputs stay for Retry and are deleted after 7 days. Recents point at the outputs. Outputs go to the user-picked folder. Temporary outputs live in the app cache, deleted when the job ends and again at every app start.

## Distribution and updates

There is no Play Store listing and no network, so the app cannot update itself.

- Releases are APKs on GitHub releases, with the SHA-256 published (S15).
- Each family phone has "Install unknown apps" allowed once for the file manager or browser used to install.
- Updates are installed by hand from the releases page, or through an update tool such as Obtainium on a phone that already has it. FileForge itself never checks for updates.
- Settings shows the installed version so family members can tell whether they are current.

## Security

Every check below is a release blocker: a failed check means no APK ships. Automated checks run in CI on every build; manual ones run before each release.

| # | Check | How it is enforced | Type |
| --- | --- | --- | --- |
| S1 | No INTERNET or ACCESS\_NETWORK\_STATE permission in the final merged manifest | CI dumps the release APK's permissions and fails on any network permission, including ones added by a library | Automated |
| S2 | No broad storage permission (no MANAGE\_EXTERNAL\_STORAGE, no READ/WRITE\_EXTERNAL\_STORAGE) | Same manifest check | Automated |
| S3 | Only the share-target and Open-with activities are exported; every other component is exported=false | Manifest check against an allow-list | Automated |
| S4 | No file is parsed in the main app process. PDFBox, PdfRenderer and JPG/PNG/WebP decoding run in the isolated process; Media3, HEIC decoding and encoding of already-decoded pixels run in the `:media` process. The main process only copies bytes and reads the first 16 to identify the format | Code review rule plus an instrumented test that fails if a parser class loads in the main process, or a PDF or image parser class loads outside the isolated process | Automated |
| S5 | Hard limits on input: file size, page count, image pixels, video length. Each job has a timeout scaled to its input (per engine, per minute of media or per page), not one fixed number | Unit tests feed oversized inputs and expect a clean error; a test checks the 10-minute video gate fits inside its timeout | Automated |
| S6 | Zip-bomb guard for DOCX and other zipped formats: cap on entry count, total unpacked size and compression ratio. Applies from v2 (Phase 5), when the app first reads zipped formats | Unit tests with crafted zip bombs | Automated |
| S7 | Incoming file names never become paths; output names are sanitised (no "/", ".." or control characters) | Unit tests with hostile names | Automated |
| S8 | Outputs shared only with read-only, per-file grants. Outputs live in the user's folder, so the grant is on that file's document URI and no FileProvider copy is needed | Self-test checks the share intent never carries a write grant | Automated |
| S9 | Nothing is backed up or moved by device transfer | allowBackup=false plus dataExtractionRules that exclude everything; manifest check | Automated |
| S10 | Release logs carry no file names, paths or content | R8 removes every android.util.Log call from release builds, so no lint rule is needed | Automated |
| S11 | Dependencies pinned and verified: Gradle dependency verification (sha256), per-module lockfiles, and an OSV scan with zero high or critical findings in anything that ships in the APK. Findings in build-only tooling are listed but do not block | CI step (`scripts/osv_scan.py`) | Automated |
| S12 | MobSF static scan of the release APK: zero high findings | Run before each release | Manual |
| S13 | Redaction removes content: after redacting, the hidden text cannot be found by text search, copied, or recovered from an earlier revision, metadata, bookmarks or attachments | Test with a known phrase placed on the page, in document info, XMP, a bookmark title and an attachment; the output is searched byte-for-byte and must not contain it | Automated |
| S14 | PDF passwords are used once and never saved; a protected input never yields an unprotected output unless the user chose "Remove password" | Code review plus a test that scans app storage after a job, and a test that a protected input's output still asks for the password | Automated |
| S15 | Release signing key kept offline with a second backup; SHA-256 of every APK published in the GitHub release | Release checklist | Manual |
| S16 | No signing material in the public repository: keystore, keystore.properties and passwords are git-ignored; APKs are signed locally | .gitignore plus a gitleaks secret scan on every push | Automated |

S1 is the offline guarantee. An app without the network permission cannot open a socket, so no library can phone home even if it tries.

## Build phases and testing gates

No phase starts until the one before it passes its gate. Performance figures are starting targets on a OnePlus 13, to be re-set after Phase 1 measures real numbers.

**Gates every phase must pass**

- All unit and instrumented tests green; security checks S1–S5, S7–S11 and S16 green in CI (S6 joins from Phase 5).
- **Test corpus:** at least 20 files per supported input format, including edge cases: zero-byte, truncated, wrong extension, password-protected, very large, rotated photos, non-English text (including Devanagari), XFA forms. The repository holds only synthetic and public-domain files; real family files stay in a local, git-ignored folder and never get committed.
- **Fuzz pass:** 1,000 mutated files per format through each converter. Zero app crashes; a failed file shows an error and the app keeps working.
- **Output check:** every output is reopened by a second reader (PdfRenderer for PDFs, ImageDecoder for images, Media3 for media) and checked for page count, dimensions or duration.
- **Airplane-mode run:** the full manual checklist passes with the phone in airplane mode.
- **Device matrix:** OnePlus 13 on Android 16 or newer, plus an Android 16 emulator in CI once the OnePlus 13 moves past Android 16, so the oldest supported version stays tested. Before a family member gets the APK, the airplane-mode checklist also runs once on their phone.
- **No data loss:** originals are never modified or deleted, checked by hash before and after every test job.

| Phase | Scope | Exit gate (on top of the common gates) |
| --- | --- | --- |
| 0. Spikes | S1 manifest check in CI; isolated process round-trip including a PdfRenderer page returned through SharedMemory; MediaCodec hardware encode attempted from an isolated process; LAME NDK build; PDFBox text with Devanagari; check whether Android's own PDF APIs can replace parts of PDFBox on Android 16 | CI fails a test APK that has the INTERNET permission; the process split for each engine is decided and S4 updated if the MediaCodec result differs from the plan |
| 0b. v2 spike (parallel, does not gate v1) | Chaquopy + PyMuPDF + pdf2docx install on Android | pdf2docx converts 10 sample PDFs on the phone, or v2 PDF → Word is re-planned |
| 1. Core + images | Donut, share entry, jobs queue, Settings, all image conversions | 100 mixed photos convert with no crash; HEIC → JPG keeps orientation; location metadata absent unless kept on purpose; a shared file still converts after the app is swiped away before the job starts; donut and sheet motion runs without dropped frames on the OnePlus 13, checked with Developer options → Profile HWUI rendering → On screen as bars (bars stay well under the green line, so slower family phones keep headroom); and with "Remove animations" on every transition jumps to its end state |
| 2. PDF tools | Merge, split, compress, page tools, PDF ↔ images, password-protected inputs | 300-page PDF merges and splits without running out of memory; compressed scanned PDF at least 40% smaller and still readable; merging a protected PDF gives a protected output |
| 3. PDF editor | Annotate, sign, forms, redact | Annotations and signatures show in a second PDF reader; a Devanagari text box and form value render correctly there; an XFA form shows a clean error; S13 redaction test passes |
| 4. Media | Video and audio conversions | 10-minute 1080p video → 720p MP4 finishes within its timeout, plays, and keeps audio in sync; cancel mid-job leaves no partial file; MP3 and M4A outputs both play in a second player |
| 5. PDF → Word (v2) | engine-python | Text and simple tables survive on 20 test PDFs, checked by hand; layout loss is labelled in the app; S6 zip-bomb tests pass |
| 6. Word → PDF (v2) | engine-office | 20 test DOCX files match a desktop LibreOffice export page for page |

v1 release = Phases 0–4 passed, plus S12 and S15.

## Open questions

- [x] Minimum Android version: 16 (minSdk 36). Every family phone is on Android 16 or newer, so nothing is lost by it.
- [x] Audio output: MP3 and M4A both in v1.
- [x] App name: FileForge. Tagline: "Your Files, Your Way." Icon direction under Brand identity.
- [x] Repository: public from day one, MIT licence; keystore never committed.
- [x] Who gets the app: family only, all on Android 16 or newer. A friend on Android 15 or older cannot install it, by design.
- [x] Test devices: the OnePlus 13 is the only test phone. Any later phone will be faster, so it is not a separate target.

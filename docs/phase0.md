# Phase 0 — spikes

What each spike answers, how to run it on the OnePlus 13, and where to record the result.
The exit gate is in [spec.md](spec.md#build-phases-and-testing-gates).

## Status

| Spike | Where | Status |
| --- | --- | --- |
| S1 manifest check in CI, including a canary APK with INTERNET that must fail | `scripts/check_manifest.py`, `.github/workflows/ci.yml`, `tools/permission-canary` | Done: passes the app, rejects the canary. Also checks S2, S3 and S9 |
| S16 secret scan | gitleaks job in CI | Done |
| Isolated process round-trip: PdfRenderer page returned through SharedMemory | `Phase0Checks.isolatedPdfRender` | Built; run on the phone |
| Isolated process cannot read app files | `Phase0Checks.isolatedCannotReadAppFiles` | Built; run on the phone |
| Hardware H.264 encode from the isolated process vs the main process | `Phase0Checks.encodeMainProcess`, `encodeIsolatedProcess` | Built; run on the phone |
| LAME built with the NDK, MP3 read back by Android | `engine-media`, `Mp3EncoderTest`, `Phase0Checks.mp3RoundTrip` | Builds for arm64 and x86_64; run on the phone |
| PDFBox with Devanagari | `Phase0Checks.devanagari` | Built; run on the phone and compare the images |
| Android 16 PDF APIs: text, search, forms | `Phase0Checks.platformPdfApis` | Compiles against API 36 (the APIs exist); run on the phone |

All checks live in `app/.../spike/Phase0Checks.kt`. The **Run Phase 0 checks** button in `MainActivity` and `Phase0ChecksTest` both call them.
| 0b: Chaquopy + PyMuPDF + pdf2docx | — | Not started. PyPI has no Android build of PyMuPDF and Chaquopy's package index does not list it, so this needs PyMuPDF (and MuPDF) compiled for Android by us |

## Running the phone spikes

**On the phone only:** install the debug APK, open FileForge and tap **Run Phase 0 checks**. Each check shows ✅ (passed), ❌ (failed) or ℹ️ (a finding that decides the plan), followed by the two Devanagari images. Screenshot the whole list.

**From a computer** (same checks, as instrumented tests):

1. On the OnePlus 13: Settings → About device → Version → tap Build number seven times, then turn on USB debugging in Developer options. Connect it by USB and accept the prompt.
2. From the repository folder:

   ```
   ./gradlew :app:connectedDebugAndroidTest :engine-media:connectedDebugAndroidTest
   adb logcat -d -s Phase0
   ```

3. The logcat lines are the results. Copy them into the table below.
4. For the Devanagari images, use the in-app button: the PDFBox image and the Android image appear one above the other. The Android one is correct. Broken conjuncts (क्ष, त्र, स्त) or a "ि" on the wrong side in the PDFBox image mean PDFBox does not shape Devanagari.

## Results — OnePlus 13, Oct 26, 2026 (in-app button)

| Question | Result | Decision |
| --- | --- | --- |
| Service reports isolated | yes | S4 sandbox works as declared |
| Isolated read of app file | denied (FileNotFoundException) | S4 holds: the parser process cannot see app files |
| PdfRenderer page via SharedMemory | 1080×1528 in 178 ms, pixels correct | Viewer and thumbnails run in the isolated process, as planned |
| Hardware H.264, main process | ok: `c2.qti.avc.encoder`, 3592 bytes from 30 frames | Control passes |
| Hardware H.264, isolated process | the isolated process died (DeadObjectException); the app kept running | Hardware MediaCodec is not usable from an isolated process: media and HEIF encoding stay in the `:media` process. Also shows that a parser crash does not take the app down |
| LAME MP3 round-trip | audio/mpeg, 1070 ms for 1000 ms of audio | MP3 output stays in v1. The extra ~70 ms is LAME's encoder padding; check gapless trimming in Phase 4 |
| PDFBox Devanagari images match | no: क्ष shown as क्‌ष, "ि" placed after its consonant (रयि, हन्दिी), श्री broken | Stamp-as-image plan for complex scripts confirmed |
| Platform PDF search finds "probe" | yes: text extracted, 1 match | Use Android's own search for the S13 redaction test |
| Platform PDF form type and widgets | type 0, 0 widgets (the test PDF has no form) | Open: retest with a real AcroForm PDF in Phase 3 before choosing between Android's form API and PDFBox |

**Exit gate:** met. CI rejects the INTERNET canary and has passed on every push; the process split is decided (no S4 change); minSdk 36 confirmed. 0b (pdf2docx) stays open, separately from v1.

## Release signing (on your own computer, never in the cloud)

The release key must never leave your machine (S15, S16). Create it once:

```
keytool -genkeypair -keystore ~/fileforge-release.jks -alias fileforge -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the repository root (git-ignored):

```
storeFile=/full/path/to/fileforge-release.jks
storePassword=...
keyAlias=fileforge
keyPassword=...
```

`./gradlew :app:assembleRelease` then writes a signed `app/build/outputs/apk/release/app-release.apk`. Without the file, release builds stay unsigned. Copy the `.jks` and its passwords to a second offline place, such as a USB stick kept elsewhere. Losing the key means family phones cannot update the app without uninstalling it first.

## Carried into Phase 1

- S11 is done: sha256 verification metadata, lockfiles and an OSV scan run in CI. Mac and Windows builds trust their own aapt2 jar until a build there adds its checksum.
- The spike code is gone; Phase 1 replaced it with the real parser services.

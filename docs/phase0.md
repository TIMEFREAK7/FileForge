# Phase 0 — spikes

What each spike answers, how to run it on the OnePlus 13, and where to record the result.
The exit gate is in [spec.md](spec.md#build-phases-and-testing-gates).

## Status

| Spike | Where | Status |
| --- | --- | --- |
| S1 manifest check in CI, including a canary APK with INTERNET that must fail | `scripts/check_manifest.py`, `.github/workflows/ci.yml`, `tools/permission-canary` | Done: passes the app, rejects the canary. Also checks S2, S3 and S9 |
| S16 secret scan | gitleaks job in CI | Done |
| Isolated process round-trip: PdfRenderer page returned through SharedMemory | `app/.../spike/ParserServiceTest` | Built; run on the phone |
| Isolated process cannot read app files | `ParserServiceTest.isolatedProcessCannotReadAppFiles` | Built; run on the phone |
| Hardware H.264 encode from the isolated process vs the main process | `ParserServiceTest.hardwareEncodeMainVersusIsolated` | Built; run on the phone |
| LAME built with the NDK, MP3 read back by Android | `engine-media`, `Mp3EncoderTest` | Builds for arm64 and x86_64; run on the phone |
| PDFBox with Devanagari | `DevanagariPdfBoxTest` | Built; run on the phone and compare the images |
| Android 16 PDF APIs: text, search, forms | `PdfPlatformApiTest` | Compiles against API 36 (the APIs exist); run on the phone |
| 0b: Chaquopy + PyMuPDF + pdf2docx | — | Not started. PyPI has no Android build of PyMuPDF and Chaquopy's package index does not list it, so this needs PyMuPDF (and MuPDF) compiled for Android by us |

## Running the phone spikes

1. On the OnePlus 13: Settings → About device → Version → tap Build number seven times, then turn on USB debugging in Developer options. Connect it by USB and accept the prompt.
2. From the repository folder:

   ```
   ./gradlew :app:connectedDebugAndroidTest :engine-media:connectedDebugAndroidTest
   adb logcat -d -s Phase0
   ```

3. The logcat lines are the results. Copy them into the table below.
4. Open Files → Download → FileForge-phase0 on the phone and compare `devanagari-pdfbox.png` with `devanagari-android.png`. The Android one is correct. Broken conjuncts (क्ष, त्र, स्त) or a "ि" on the wrong side in the PDFBox image mean PDFBox does not shape Devanagari.

## Results (fill in after the phone run)

| Question | Result | What it decides |
| --- | --- | --- |
| Service reports isolated | | S4 sanity |
| Isolated read of app file | expect `denied` | S4 |
| PdfRenderer page via SharedMemory, time for one 1080 px page | | Viewer and thumbnails can run isolated |
| Hardware H.264, main process | expect `ok` | Control |
| Hardware H.264, isolated process | | `failed` keeps the `:media` process plan; `ok` means media can run isolated too, and S4 tightens |
| LAME MP3 round-trip | | MP3 output in v1 |
| PDFBox Devanagari images match | | `no` keeps the stamp-as-image plan for complex scripts |
| Platform PDF search finds "probe" | | Use it for the S13 redaction test |
| Platform PDF form type and widgets | | Whether AcroForm filling can use Android's own API instead of PDFBox |

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

## Left for Phase 1

- S11 (dependency verification metadata, lockfiles, OSV scan) is not in CI yet. Gradle's verification metadata records platform-specific build tools, so it has to be generated on every OS you build on. Tell me which OS you use with Android Studio before it is added.
- The spike code in `app/.../spike` is throwaway: Phase 1 replaces it with the real parser service.

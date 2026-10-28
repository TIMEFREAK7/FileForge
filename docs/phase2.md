# Phase 2 — PDF tools

Scope from [spec.md](spec.md#build-phases-and-testing-gates): merge, split, compress, page tools (rotate, delete, reorder, extract), PDF → images, images → PDF, password-protected inputs.

## What is built

| Part | Where |
| --- | --- |
| PDF as a format: sniffed from `%PDF-`, targets (PDF → JPG/PNG/PDF; images → PDF), action names ("Compress", "Merge", "JPG pages"), PDF limits (500 MB, 2,000 pages, 50 MP per rendered page) and timeouts scaled by pages and size | `core` |
| PDF engine. Isolated process: PDFBox opens, assembles and compresses every PDF, reading through the file descriptor (no temp files there); Android's PdfRenderer draws pages. `:media`: builds PDFs from JPEGs FileForge just encoded | `engine-pdf` |
| Compress: large embedded images re-encoded as JPEG no finer than the chosen DPI, each kept only if smaller; stencils, 1-bit scans and images with transparency are left alone | `PdfOps.compress` |
| Page tools, merge, extract and split: one "assemble" call (the pages to keep, in order, with extra rotation) | `PdfOps.assemble` |
| Jobs: six job types (convert, PDF → images, combine into one PDF, compress, organiser save, split). Many-to-one jobs succeed or fail whole. PDF → images and split carry on from the last saved file after Retry or a restart | `jobs` |
| UI: Pick files takes PDFs; the donut names what each segment does; the options sheet shows page detail (DPI), page size or image quality as fits; Edit PDF pages opens the organiser; share and Open with accept PDFs | `app/ui` |
| Self-tests for the phase gates (debug builds) | `app/ui/SelfTest.kt` |

## How to check the Phase 2 gate on the phone

Install the debug APK over the old one, then:

1. **Self-tests:** Settings → Run self-tests → **Quick**, then **Full** (adds 1,000 mutated PDFs). Screenshot the summary and any red lines. The PDF lines: page tools, PDF ↔ images, damaged PDFs, 300-page merge and split, JPEG encode probe, scanned-PDF compression, PDF fuzz.
2. **Compress a real scan:** share a scanned PDF to FileForge → Compress. Compare the sizes in Jobs and Files, and open the result to check it reads well.
3. **Merge:** Pick files → choose 2–3 PDFs → Merge. Open the result.
4. **PDF → images:** share a PDF → JPG pages. The pages appear in Recents as `name_p001.jpg`, …
5. **Images → PDF:** pick 3 photos → PDF, hold the segment for options and try A4 and Fit image.
6. **Organiser:** Edit PDF pages → pick a PDF. Select pages; try Rotate, Delete, Move earlier, Add PDF; then Save as PDF. Also try Extract selected and Split before selected.
7. **Airplane mode:** repeat 2 and 6 with airplane mode on.
8. **TalkBack:** in the organiser, each page reads its position, page number and file; double-tap selects it.

Send screenshots of step 1 and anything that looked wrong.

## Results — OnePlus 13, Oct 28, 2026

| Gate item | Result |
| --- | --- |
| Self-tests, Quick | 59 of 59 passed in 47 s |
| 300-page merge and split | Merge 300 + 300 → 600 pages in 562 ms; split into 10 × 30 pages in 1.5 s; no memory failure |
| Scanned PDF compression | 7,366 KB → 1,452 KB (81% smaller), page difference 2 of 255 |
| JPEG encode in the isolated process | Bitmap.compress 10,125 ms vs YuvImage 302 ms: the YuvImage route is confirmed |
| Fuzz PDF × 50 (Quick) | App survived; 0 reader crashes, 0 timeouts. The 1,000-file Full run is still to do |
| Real files from the app | Compress (a lean PDF correctly saved nothing), photo → PDF, HEIC → JPG, merge, JPG pages |
| Fixed | Each share or Open with left another FileForge card in recent apps; the activity is now single-task |

## Not done yet (honest gaps)

- **Password-protected PDFs are not supported yet.** They fail with "This PDF needs a password to open. Password support comes in a later update." The Phase 2 gate item "merging a protected PDF gives a protected output" (and S14) cannot pass until this lands, so **the Phase 2 gate stays open** even if every self-test passes.
- Merge, split and page tools keep the pages' content, but **form fields stop working, bookmarks are dropped and links within the document may break**; compress keeps all three. Images inside form XObjects are not compressed.
- Compress replaces an image only when the JPEG is smaller; a PDF that is already lean comes back with a note and no copy.
- The isolated-process JPEG encoder (YuvImage route) is unmeasured on the phone; the self-test's probe line reports it.
- No "grid of PDF tools" on Home: merge, compress and PDF → images are donut segments, and the organiser has its own button.
- Gaps carried from Phase 1 (liquid-glass look, Folders screen, S4 instrumented test) still stand.

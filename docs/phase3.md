# Phase 3 — PDF editor

Scope from [spec.md](spec.md#build-phases-and-testing-gates): annotate, sign, fill forms, redact.

**Started before the Phase 2 gate closed.** Still open there: the Full self-test (1,000 mutated files per format), airplane mode for the PDF tools, and a real scanned and a real protected PDF. Phase 3 must not go to the family before those pass.

## What is built

| Part | Where |
| --- | --- |
| Page geometry: marks are placed on the page as shown and mapped through /Rotate, so they stay upright on rotated pages (unit-tested) | `core/PageGeometry` |
| Edit, in the isolated process: pen, highlight (yellow, multiplied, so text stays readable), box, circle, line, Latin text as real PDF text, PNG stamps (signatures, other scripts), form values | `engine-pdf/PdfEdit.edit` |
| Forms: text, check box, radio and choice fields; values in scripts the form's font cannot show (Devanagari etc.) are drawn by Android and the field is flattened; XFA gives a plain error | `PdfEdit.formFields`, `fillForm` |
| Redaction (S13): pages with hidden areas are rasterised with the areas painted black; the output is a new file with only page content (no document info, XMP, bookmarks, attachments, comments, links, form fields, structure tree or earlier revisions) | `PdfEdit.redact` |
| Edit job: the edit spec and stamps sit beside the input copy, so form values are deleted with it when the job is done | `jobs` (`EDIT_PDF`) |
| Editor screen, signature pad, Signatures in Settings, Home button "Sign & mark up a PDF", donut "Sign & mark up" for one PDF | `app/ui/PdfEditorScreen.kt`, `Signatures.kt` |
| Self-tests for the gate (debug builds), checked with Android's renderer as the second reader | `SelfTest.editor` |

## How to check the Phase 3 gate on the phone

1. **Self-tests:** Settings → Run self-tests → **Quick**. The new "PDF editor" lines: marks in a second reader, rotated page, signature, Devanagari, form fill, XFA, S13.
2. **Sign:** Home → Sign & mark up a PDF → pick one → **Sign** → draw a signature → tap where it goes (Small, Medium, Large) → Save. Open the result in another app (Files, Drive, a PDF reader): the signature must be there.
3. **Mark up:** pen, highlight over some text (the text should stay readable), a box, a circle, a line, and text in English. Undo once. Save and open in another app.
4. **Devanagari:** Text tool → type in Hindi or Marathi (e.g. "नमस्ते, पुणे") → Save → open in another app. **Check by eye that the letters join correctly** (matras and conjuncts); the self-test can only check that something was drawn.
5. **Form:** a PDF with fillable fields (many government and bank forms have them) → **Form** → fill a field in English and one in Hindi, tick a box → Save → open in another app.
6. **Hide:** Hide tool → drag over a name or number → Save → open the result, and try to search for or select the hidden text: it must not be found. The page becomes an image.
7. **Protected PDF:** open a protected PDF in the editor: it asks for the password; the result keeps it unless you ticked "Save without a password".
8. **TalkBack:** the Form sheet should read every field and work fully.

Send screenshots of step 1, and of anything that looks wrong.

## Not done yet (honest gaps)

- **Marks are drawn into the page.** Every reader shows them, but other apps cannot move or delete them afterwards (they are not PDF comment objects).
- **No pinch zoom**; one page fits the screen. Fine detail on a small page is hard to place.
- **A placed signature cannot be dragged or resized**: Undo and tap again (three sizes).
- **Text in other scripts is a picture**: it cannot be selected or searched later (spec: PDFBox does no script shaping).
- **Redaction rasterises the whole page**, so that page's other text is no longer selectable, and the redacted file drops comments, links, form fields, bookmarks and attachments on every page. That is deliberate (S13): any of them could point back to the hidden content.
- **Drawing tools need sight**; with TalkBack the Form sheet and the text tool's dialog work, placing marks does not.
- A Devanagari form value on a rotated page is stamped in the page's unrotated orientation (rare for forms).

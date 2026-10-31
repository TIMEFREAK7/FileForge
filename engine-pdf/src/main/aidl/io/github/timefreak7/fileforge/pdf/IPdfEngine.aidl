package io.github.timefreak7.fileforge.pdf;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Every call returns "ok" (boolean) and either its results or "error" (a plain-language message) with an
 * optional "code" ("password", "wrongPassword"). Passwords are "" when none; they are used and forgotten (S14).
 */
interface IPdfEngine {
    int pid();

    /** "pages" (int). */
    Bundle info(in ParcelFileDescriptor pdf, String password);

    /** One page as pixels ("mem", "w", "h", "alpha", "cs"); widthPx > 0 wins over dpi. */
    Bundle renderPage(in ParcelFileDescriptor pdf, String password, int page, int dpi, int widthPx);

    /**
     * Builds one PDF from pages of [inputs]. [spec] is triples (input index, page index, extra
     * rotation in degrees). Covers merge, split, extract, delete, reorder and rotate. The output keeps the
     * first protected input's password unless [removePassword]; a non-empty [newPassword] protects it with that.
     */
    Bundle assemble(in ParcelFileDescriptor[] inputs, in String[] passwords, in int[] spec, in ParcelFileDescriptor output,
            boolean removePassword, String newPassword);

    /** Re-encodes embedded images as JPEG at [quality], no larger than [dpi] across the page. "replaced" (int). */
    Bundle compress(in ParcelFileDescriptor input, String password, in ParcelFileDescriptor output, int quality, int dpi,
            boolean removePassword);

    /** One page per JPEG (encoded by FileForge, never user files). [pageSize] is a PageSize name. */
    Bundle imagesToPdf(in ParcelFileDescriptor[] jpegs, in ParcelFileDescriptor output, String pageSize);

    /** Self-test diagnostic: ms to JPEG-encode a 12 MP bitmap with Bitmap.compress and with YuvImage. */
    Bundle jpegProbe();
}

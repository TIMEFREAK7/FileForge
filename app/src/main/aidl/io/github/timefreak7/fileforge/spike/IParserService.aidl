package io.github.timefreak7.fileforge.spike;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IParserService {
    /** True when the service really runs in an isolated process. */
    boolean isIsolated();

    /** Renders one PDF page; returns "mem" (SharedMemory, ARGB_8888), "w" and "h". */
    Bundle renderPage(in ParcelFileDescriptor pdf, int pageIndex, int widthPx);

    /** Tries a short hardware H.264 encode; returns a one-line report. */
    String encodeProbe();

    /** Tries to read a file by path; returns a one-line report. */
    String readProbe(String path);
}

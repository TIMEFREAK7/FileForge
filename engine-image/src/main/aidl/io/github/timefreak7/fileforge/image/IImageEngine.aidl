package io.github.timefreak7.fileforge.image;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IImageEngine {
    /** Process id, so a hung same-uid (":media") service can be killed on timeout. */
    int pid();

    /**
     * Converts one image. [input] is read-only and seekable; [output] is read-write and empty.
     * Returns "ok" (boolean), and either "w"/"h" or "error" (a plain-language message).
     */
    Bundle convert(in ParcelFileDescriptor input, in ParcelFileDescriptor output,
            String targetMime, int quality, int maxSide, boolean keepMetadata);
}

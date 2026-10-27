package io.github.timefreak7.fileforge.core

/** Answers the converter contract's first question: which targets fit these inputs. */
object Targets {
    /**
     * Targets every input can become, in donut [order]. A mixed selection gets what all of them
     * can become; an unknown input type means nothing fits. HEIC output needs a hardware HEVC encoder.
     *
     * Images: every image format (same format = resize or recompress), and PDF (one PDF from all images).
     * PDFs: JPG or PNG (one image per page), and PDF (one file: compress; several: merge).
     */
    fun forInputs(inputs: List<Format?>, order: List<Format>, heicEncodeAvailable: Boolean): List<Format> {
        if (inputs.isEmpty() || inputs.any { it == null }) return emptyList()
        val fit = when {
            inputs.all { it!!.isImage } -> Format.entries.filter { it != Format.HEIC || heicEncodeAvailable }
            inputs.all { it == Format.PDF } -> listOf(Format.JPG, Format.PNG, Format.PDF)
            else -> emptyList()
        }
        return order.filter { it in fit }
    }

    /** What a target does to these inputs, in words for the donut and the options sheet. */
    fun action(inputs: List<Format?>, target: Format): String = when {
        inputs.all { it == Format.PDF } && target == Format.PDF -> if (inputs.size == 1) "Compress" else "Merge"
        inputs.all { it == Format.PDF } -> "${target.label} pages"
        target == Format.PDF -> "PDF"
        else -> target.label
    }
}

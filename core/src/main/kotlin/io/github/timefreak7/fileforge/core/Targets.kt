package io.github.timefreak7.fileforge.core

/** Answers the converter contract's first question: which targets fit these inputs. */
object Targets {
    /**
     * Targets every input can become, in donut [order]. A mixed selection gets what all of them
     * can become; an unknown input type means nothing fits. HEIC output needs a hardware HEVC encoder.
     */
    fun forInputs(inputs: List<Format?>, order: List<Format>, heicEncodeAvailable: Boolean): List<Format> {
        if (inputs.isEmpty() || inputs.any { it == null }) return emptyList()
        // Every v1 image format converts to every other one (same-format = resize or recompress).
        return order.filter { it != Format.HEIC || heicEncodeAvailable }
    }
}

package io.github.timefreak7.fileforge.core

/**
 * The editor works in "display" space: the page as shown (its /Rotate applied, clockwise per the PDF
 * spec), in points, origin at the bottom-left, y up. PDF content is drawn in user space, so the engine
 * sets this matrix first and then draws everything in display space; text and images stay upright.
 */
object PageGeometry {
    /**
     * Matrix (a, b, c, d, e, f) taking display space to user space, for a page whose crop box is
     * [x0], [y0], [w] by [h] and whose /Rotate is [rotation]: x = aX + cY + e, y = bX + dY + f.
     */
    fun displayToUser(x0: Float, y0: Float, w: Float, h: Float, rotation: Int): FloatArray = when (Math.floorMod(rotation, 360)) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, x0 + w, y0)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, x0 + w, y0 + h)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, x0, y0 + h)
        else -> floatArrayOf(1f, 0f, 0f, 1f, x0, y0)
    }

    /** Width and height of the page as shown. */
    fun displaySize(w: Float, h: Float, rotation: Int): Pair<Float, Float> =
        if (Math.floorMod(rotation, 180) == 90) h to w else w to h
}

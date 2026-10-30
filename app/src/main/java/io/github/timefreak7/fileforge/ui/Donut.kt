package io.github.timefreak7.fileforge.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.timefreak7.fileforge.core.Format
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

private val InnerRadius = 52.dp
private val OuterRadius = 132.dp
private const val HOLD_FOR_OPTIONS_MS = 600L

/**
 * The radial menu. Segments sit in fixed positions ([slots], the Settings order); a slot whose format
 * does not fit the selection stays in place, greyed out, so muscle memory survives.
 *
 * Drag mode ([dragPosition] non-null, finger still down from a long-press): hovering ticks, holding opens
 * options, and the caller commits on lift via [hoveredSlot]. Tap mode: tap = convert, long-press = options.
 */
@Composable
fun DonutOverlay(
    slots: List<Format>,
    enabled: Set<Format>,
    center: Offset?,
    dragPosition: Offset?,
    onHover: (Format?) -> Unit,
    onConvert: (Format) -> Unit,
    onOptions: (Format) -> Unit,
    onDismiss: () -> Unit,
    /** What each segment does to this selection ("Compress", "JPG pages"); defaults to the format name. */
    label: (Format) -> String = { it.label },
    /** Shown as a button under the ring when the selection is all PDFs: opens the page organiser. */
    onEditPages: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val inner = with(density) { InnerRadius.toPx() }
    val outer = with(density) { OuterRadius.toPx() }
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        // Opens under the finger, nudged inward so the whole ring stays on screen.
        val c = (center ?: Offset(width / 2, height / 2)).let {
            Offset(it.x.coerceIn(outer, width - outer), it.y.coerceIn(outer, height - outer))
        }
        val sweep = 360f / slots.size

        fun slotAt(p: Offset): Format? {
            val dx = p.x - c.x
            val dy = p.y - c.y
            val r = hypot(dx, dy)
            if (r < inner * 0.7f || r > outer * 1.35f) return null
            // 0 degrees = straight up, clockwise; slot 0 is centred at the top.
            val angle = ((atan2(dy, dx) * 180 / PI + 90 + sweep / 2 + 360) % 360).toFloat()
            return slots.getOrNull((angle / sweep).toInt())?.takeIf { it in enabled }
        }

        val hovered = dragPosition?.let(::slotAt)
        val currentHovered by rememberUpdatedState(hovered)
        LaunchedEffect(hovered) {
            onHover(hovered)
            if (hovered != null) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                delay(HOLD_FOR_OPTIONS_MS)
                if (currentHovered == hovered) onOptions(hovered)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = 0.32f))
                .pointerInput(slots, enabled, c) {
                    detectTapGestures(
                        onTap = { p ->
                            val slot = slotAt(p)
                            if (slot != null) onConvert(slot) else if (hypot(p.x - c.x, p.y - c.y) > inner) onDismiss()
                        },
                        onLongPress = { p -> slotAt(p)?.let(onOptions) },
                    )
                },
        ) {
            slots.forEachIndexed { i, format ->
                val isEnabled = format in enabled
                val isHovered = format == hovered
                val grow by animateFloatAsState(
                    targetValue = if (isHovered) 1.08f else 1f,
                    animationSpec = Motion.fastSpatial(),
                    label = "segment",
                )
                val fill = when {
                    isHovered -> colors.primary
                    isEnabled -> colors.surfaceContainerHigh
                    else -> colors.surfaceVariant.copy(alpha = 0.5f)
                }
                val ring = (outer - inner)
                val mid = (inner + outer) / 2 * grow
                Canvas(Modifier.fillMaxSize()) {
                    val radius = mid
                    drawArc(
                        color = fill,
                        startAngle = -90f - sweep / 2 + i * sweep + 1.5f,
                        sweepAngle = sweep - 3f,
                        useCenter = false,
                        topLeft = Offset(c.x - radius, c.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = ring * grow),
                    )
                }
                val labelAngle = (i * sweep - 90) * PI / 180
                val labelCenter = Offset(c.x + mid * cos(labelAngle).toFloat(), c.y + mid * sin(labelAngle).toFloat())
                Text(
                    text = label(format),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        isHovered -> colors.onPrimary
                        isEnabled -> colors.onSurface
                        else -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    modifier = Modifier
                        .centeredAt(labelCenter)
                        .semantics { contentDescription = if (isEnabled) actionText(format, label(format)) else "${label(format)} not available" },
                )
            }
            Text(
                text = if (dragPosition != null) "Lift to convert" else "Tap a format",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                modifier = Modifier.centeredAt(c),
            )
            if (onEditPages != null) {
                // Under the ring, or above it when the ring sits near the bottom of the screen.
                val gap = with(density) { 44.dp.toPx() }
                val y = if (c.y + outer + gap * 1.5f < height) c.y + outer + gap else c.y - outer - gap
                FilledTonalButton(onClick = onEditPages, modifier = Modifier.centeredAt(Offset(c.x, y)).heightIn(min = 48.dp)) {
                    Text("Edit pages")
                }
            }
        }
    }
}

/** Places the element so its centre lands on [p] (in the parent's pixels). */
private fun Modifier.centeredAt(p: Offset) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(IntOffset((p.x - placeable.width / 2f).roundToInt(), (p.y - placeable.height / 2f).roundToInt()))
    }
}

/** True when the donut must be replaced by a plain list (spec: Accessibility). */
@Composable
fun rememberUseListInsteadOfDonut(): Boolean {
    val view = LocalView.current
    return remember {
        val am = view.context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        am.isTouchExplorationEnabled ||
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id.contains("switchaccess", ignoreCase = true) }
    }
}

/** "Convert to JPG" for a plain conversion, the action itself ("Compress", "JPG pages") otherwise. */
fun actionText(format: Format, label: String) = if (label == format.label) "Convert to ${format.label}" else label

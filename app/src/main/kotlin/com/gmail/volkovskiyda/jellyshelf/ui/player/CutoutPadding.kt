package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.getDisplayCutoutBounds
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import kotlin.math.ceil

/**
 * Pads this element away from a display cutout only where the cutout actually overlaps it, and only
 * by as much as the cutout's own bounds reach in.
 *
 * `windowInsetsPadding(WindowInsets.displayCutout)` pads by the whole strip the cutout sits in: in
 * landscape a centred punch-hole costs every full-width row a ~50 dp gutter down its whole length,
 * even rows at the top and bottom of the screen that are nowhere near the hole. This reads the
 * cutouts' real bounds instead, so the player's top bar and seek bar run edge to edge past a
 * mid-height camera and still step around a corner one (the Pixel 5's), which does overlap them.
 *
 * The bounds are only readable during placement, so a change (a rotation, the element moving) is
 * picked up there and applied on the next measure — at most a frame late, and the player's controls
 * fade in from nothing anyway.
 */
internal fun Modifier.cutoutPadding(): Modifier = this then CutoutPaddingElement

private data object CutoutPaddingElement : ModifierNodeElement<CutoutPaddingNode>() {
    override fun create() = CutoutPaddingNode()

    override fun update(node: CutoutPaddingNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "cutoutPadding"
    }
}

private class CutoutPaddingNode : Modifier.Node(), LayoutModifierNode {
    private var padding = CutoutInsets.None

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val used = padding
        val horizontal = used.left + used.right
        val vertical = used.top + used.bottom
        val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))
        val width = constraints.constrainWidth(placeable.width + horizontal)
        val height = constraints.constrainHeight(placeable.height + vertical)
        return layout(width, height) {
            val cutouts = getDisplayCutoutBounds().map {
                Rect(
                    left = it.left.current(Float.NaN),
                    top = it.top.current(Float.NaN),
                    right = it.right.current(Float.NaN),
                    bottom = it.bottom.current(Float.NaN),
                )
            }
            val needed = cutoutInsets(cutouts, width, height)
            if (needed != used) {
                padding = needed
                invalidateMeasurement()
            }
            placeable.place(used.left, used.top)
        }
    }
}

/** How far in from each edge an element has to keep its content, in pixels. */
internal data class CutoutInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun max(other: CutoutInsets) = CutoutInsets(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    companion object {
        val None = CutoutInsets(0, 0, 0, 0)
    }
}

/**
 * The padding a [width] × [height] element needs to clear the [cutouts] that overlap it, each given
 * in the element's own coordinates.
 *
 * A cutout is attached to one edge of the display, so an overlapping one crosses an edge of the
 * element too, and the content steps away from that edge just far enough. A cutout crossing two
 * adjacent edges (a corner hole) pushes the content sideways rather than down: a full-width row has
 * width to spare, and moving it down would only open a gap above it. One spanning the element's
 * whole width (a centred hole over a narrow pill) can only push it down. Cutouts that miss the
 * element, and ones with no known bounds, cost nothing.
 */
internal fun cutoutInsets(cutouts: List<Rect>, width: Int, height: Int): CutoutInsets =
    cutouts.fold(CutoutInsets.None) { insets, cutout -> insets.max(insetsFor(cutout, width, height)) }

private fun insetsFor(cutout: Rect, width: Int, height: Int): CutoutInsets {
    // NaN fails every comparison, so a cutout whose bounds are not known yet never overlaps.
    val overlaps = cutout.right > 0 && cutout.left < width && cutout.bottom > 0 && cutout.top < height
    if (!overlaps) return CutoutInsets.None
    val crossesLeft = cutout.left <= EDGE_SLOP
    val crossesRight = cutout.right >= width - EDGE_SLOP
    val crossesTop = cutout.top <= EDGE_SLOP
    val crossesBottom = cutout.bottom >= height - EDGE_SLOP
    return when {
        crossesLeft && !crossesRight -> CutoutInsets(left = ceil(cutout.right).toInt(), 0, 0, 0)
        crossesRight && !crossesLeft -> CutoutInsets(0, 0, right = ceil(width - cutout.left).toInt(), 0)
        // Top before bottom even when it crosses both: a hole taller than the element still hangs
        // from the top edge on every phone, and only padding the top moves the content past it.
        crossesTop -> CutoutInsets(0, top = ceil(cutout.bottom).toInt(), 0, 0)
        crossesBottom -> CutoutInsets(0, 0, 0, bottom = ceil(height - cutout.top).toInt())
        // Floating inside the element: no edge to step from.
        else -> CutoutInsets.None
    }
}

/** Ruler values are floats; a cutout flush with an edge can land a fraction of a pixel inside it. */
private const val EDGE_SLOP = 1f

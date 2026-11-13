package com.example.connectallspeakers.View.group

import com.example.connectallspeakers.state.MAX_GROUP_DEVICES
import kotlin.math.cos
import kotlin.math.sin

// Layout settings — Figma 66:2131 / 2259 / 2398 / 2559 / 2742.
internal const val CircleMinimumWidth = 720f
internal const val CircleHeight = 500f

/** Figma density tokens in dp, with labels in sp; large groups move detail into the inspector/roster.
 * @property width Device slot width. @property height Device slot height.
 * @property avatar Circular icon diameter. @property labelSize Device-label type size.
 * @property source Central source diameter. All dimensions are density independent.
 */
internal enum class CircleDensity(val width: Float, val height: Float, val avatar: Float,
    val labelSize: Int, val source: Float) {
    Roomy(196f, 112f, 56f, 16, 196f),
    Medium(164f, 104f, 48f, 14, 176f),
    Compact(104f, 76f, 40f, 12, 152f),
}

/** Top-left device coordinates in the radial canvas's dp space. @property x Horizontal. @property y Vertical. */
internal data class CirclePosition(val x: Float, val y: Float)

/** Measured layout, independent of endpoint identity and native playback.
 * @property density Label/avatar/source dimensions. @property positions Clockwise device slots in membership order.
 */
internal data class CircleLayout(val density: CircleDensity, val positions: List<CirclePosition>)

/** Places up to twelve members without crowding the source; four devices use the diagonal Figma arrangement.
 * @param count Displayed members, 0..12; never a statement of simultaneous playback support.
 * @param width Available canvas width in dp, at least 720; narrower viewports scroll this canvas.
 * @return Fixed-height radial coordinates and the appropriate Figma density.
 */
internal fun sharedCircleLayout(count: Int, width: Float): CircleLayout {
    require(count in 0..MAX_GROUP_DEVICES && width.isFinite() && width >= CircleMinimumWidth)
    val density = when { count <= 4 -> CircleDensity.Roomy; count <= 8 -> CircleDensity.Medium; else -> CircleDensity.Compact }
    val radiusX = width / 2 - if (count == 3) 162f else 132f
    val radiusY = if (count == 3) 175f else 185f
    val startDegrees = if (count == 4) -135.0 else -90.0
    return CircleLayout(density, List(count) { index ->
        val angle = (startDegrees + index * 360.0 / count) * kotlin.math.PI / 180
        CirclePosition(width / 2 + radiusX * cos(angle).toFloat() - density.width / 2,
            CircleHeight / 2 + radiusY * sin(angle).toFloat() - density.height / 2)
    })
}

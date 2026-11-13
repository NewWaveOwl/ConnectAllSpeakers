package com.example.connectallspeakers

import com.example.connectallspeakers.View.group.*
import kotlin.test.*

/** All requested densities remain within the scrollable canvas, including partially assembled groups. */
class SharedCircleLayoutTest {
    @Test fun requestedCountsFitAtReferenceAndMinimumWidths() {
        for (width in listOf(720f, 984f)) for (count in listOf(0,1,2,3,4,6,8,12)) {
            val layout = sharedCircleLayout(count, width)
            assertEquals(count, layout.positions.size)
            for (position in layout.positions) {
                assertTrue(position.x >= 0 && position.x + layout.density.width <= width)
                assertTrue(position.y >= 0 && position.y + layout.density.height <= CircleHeight)
            }
            assertEquals(when { count <= 4 -> CircleDensity.Roomy; count <= 8 -> CircleDensity.Medium; else -> CircleDensity.Compact }, layout.density)
        }
    }
    @Test fun invalidCountAndUnboundedCanvasAreRejected() {
        assertFailsWith<IllegalArgumentException> { sharedCircleLayout(13, 984f) }
        assertFailsWith<IllegalArgumentException> { sharedCircleLayout(3, Float.POSITIVE_INFINITY) }
    }
}

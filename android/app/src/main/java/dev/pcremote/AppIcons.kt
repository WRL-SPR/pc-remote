package dev.pcremote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A few Material Symbols paths, so we don't pull in the whole extended icon library. */
object AppIcons {
    val Desktop by lazy {
        icon(
            "Desktop",
            "M21,2H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h7v2H8v2h8v-2h-2v-2h7c1.1,0 2,-0.9 2,-2V4" +
                "c0,-1.1 -0.9,-2 -2,-2zM21,16H3V4h18v12z",
        )
    }
    val Power by lazy {
        icon(
            "Power",
            "M13,3h-2v10h2L13,3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7" +
                "s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12" +
                "c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z",
        )
    }
    val Moon by lazy {
        icon(
            "Moon",
            "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02" +
                "C13.15,16.73 8.58,8.55 12.34,2.02z",
        )
    }
    val Bed by lazy {
        icon(
            "Bed",
            "M7,13c1.66,0 3,-1.34 3,-3S8.66,7 7,7s-3,1.34 -3,3 1.34,3 3,3zM19,7h-8v7H3V5H1v15h2v-3h18v3h2v-9" +
                "c0,-2.21 -1.79,-4 -4,-4z",
        )
    }

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}

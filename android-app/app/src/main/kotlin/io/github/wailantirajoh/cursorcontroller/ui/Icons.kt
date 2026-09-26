package io.github.wailantirajoh.cursorcontroller.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Ikon garis dari mockup (viewBox 24). Warna mengikuti `tint` pada Icon. */
object AppIcons {
    val Back = stroke("back", "M19 12H5M11 6l-6 6 6 6")
    val Close = stroke("close", "M6 6l12 12M18 6L6 18")
    val Settings = stroke(
        "settings", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12", circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f),
    )
    val Laptop = stroke("laptop", "M5.5 5h13a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 14.5v-8A1.5 1.5 0 0 1 5.5 5z", "M2 19h20")
    val LaptopOff = stroke(
        "laptop-off", "M5.5 5h13a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 14.5v-8A1.5 1.5 0 0 1 5.5 5z",
        "M2 19h20M9 8.5l6 5M15 8.5l-6 5",
    )
    val Qr = stroke(
        "qr", "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3",
        "M9 9h2v2H9zM13 13h2v2h-2z",
    )
    val More = stroke("more", circle(12f, 5f, 1f), circle(12f, 12f, 1f), circle(12f, 19f, 1f))
    val Check = stroke("check", circle(12f, 12f, 9f), "M8 12.5l2.5 2.5L16 9.5")
    val Clock = stroke("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val ShieldX = stroke("shield-x", "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z", "M9.5 10l5 5M14.5 10l-5 5")
    val CameraOff = stroke(
        "camera-off", "M3 3l18 18", "M9 5h6l1.5 2H19a2 2 0 0 1 2 2v8.5M17 19H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1", "M10 10.5a3 3 0 0 0 4 4",
    )
    val Refresh = stroke("refresh", "M20 12a8 8 0 1 1-2.3-5.6", "M20 4v4h-4")
    val Plus = stroke("plus", "M12 5v14M5 12h14")

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { path ->
                addPath(
                    pathData = addPathNodes(path),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}

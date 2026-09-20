package com.armsx2.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/** Font-independent, monochrome line icons. The adjacent label names each action. */
@Composable
fun BlackIceIcon(
    glyph: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.size(24.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.6f, cap = StrokeCap.Round)
            fun line(vararg points: Float) {
                val p = Path()
                p.moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1])
                drawPath(p, color, style = stroke)
            }
            fun circle(x: Float, y: Float, r: Float) =
                drawCircle(color, r, Offset(x, y), style = stroke)
            when (glyph) {
                "◉" -> { line(6f,6f,18f,6f,18f,18f,6f,18f,6f,6f); line(9f,9f,15f,9f,15f,15f,9f,15f,9f,9f); for (pin in listOf(8f,12f,16f)) { line(pin,2f,pin,6f); line(pin,18f,pin,22f); line(2f,pin,6f,pin); line(18f,pin,22f,pin) } }
                "folder" -> line(3f,5f,10f,5f,13f,8f,21f,8f,21f,20f,3f,20f,3f,5f)
                "☰" -> { line(4f,6f,20f,6f); line(4f,12f,20f,12f); line(4f,18f,20f,18f) }
                "🖥️", "▣" -> { line(3f,4f,21f,4f,21f,16f,3f,16f,3f,4f); line(12f,16f,12f,20f); line(8f,20f,16f,20f) }
                "🔧" -> { line(16f,3f,13f,6f,15f,9f,19f,8f,21f,5f,21f,11f,17f,14f,13f,13f,6f,21f,3f,18f,11f,10f,10f,6f,13f,3f,16f,3f) }
                "⚡" -> line(14f,2f,5f,14f,11f,14f,10f,22f,19f,10f,13f,10f,14f,2f)
                "🎮", "⌁" -> { line(6f,7f,18f,7f,22f,18f,18f,19f,15f,15f,9f,15f,6f,19f,2f,18f,6f,7f); line(6f,11f,10f,11f); line(8f,9f,8f,13f); circle(16f,10f,.6f); circle(18f,12f,.6f) }
                "⚙" -> { circle(12f,12f,6f); circle(12f,12f,2f); line(12f,2f,12f,6f); line(12f,18f,12f,22f); line(2f,12f,6f,12f); line(18f,12f,22f,12f); line(5f,5f,8f,8f); line(16f,16f,19f,19f); line(5f,19f,8f,16f); line(16f,8f,19f,5f) }
                "🏆", "★", "☆" -> { line(7f,3f,17f,3f,17f,11f,14f,15f,10f,15f,7f,11f,7f,3f); line(7f,5f,3f,5f,3f,10f,7f,12f); line(17f,5f,21f,5f,21f,10f,17f,12f); line(12f,15f,12f,21f); line(8f,21f,16f,21f) }
                "▶" -> line(7f,4f,20f,12f,7f,20f,7f,4f)
                "⏩" -> { line(3f,5f,12f,12f,3f,19f,3f,5f); line(12f,5f,21f,12f,12f,19f,12f,5f) }
                "↻" -> { drawArc(color, -55f, 295f, false, Offset(4f,4f), androidx.compose.ui.geometry.Size(16f,16f), style=stroke); line(18f,2f,18f,7f,13f,7f) }
                "⏏" -> { line(4f,15f,12f,4f,20f,15f,4f,15f); line(4f,20f,20f,20f) }
                "■" -> line(5f,5f,19f,5f,19f,19f,5f,19f,5f,5f)
                "↥", "↧" -> { line(4f,17f,4f,21f,20f,21f,20f,17f); line(12f,3f,12f,16f); if(glyph=="↥") line(7f,8f,12f,3f,17f,8f) else line(7f,11f,12f,16f,17f,11f) }
                "▤", "▦" -> { line(5f,3f,17f,3f,20f,6f,20f,21f,5f,21f,5f,3f); line(9f,3f,9f,9f,16f,9f,16f,3f); line(9f,21f,9f,15f,16f,15f,16f,21f) }
                "✥" -> { line(12f,2f,12f,22f); line(2f,12f,22f,12f); line(8f,6f,12f,2f,16f,6f); line(8f,18f,12f,22f,16f,18f); line(6f,8f,2f,12f,6f,16f); line(18f,8f,22f,12f,18f,16f) }
                "✓" -> line(4f,12f,9f,17f,20f,6f)
                else -> { line(12f,3f,21f,12f,12f,21f,3f,12f,12f,3f); circle(12f,12f,2f) }
            }
        }
    }
}

package com.physicalactivitygate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/**
 * App-wide theme: the Material color scheme plus a painted watercolor paper
 * behind every screen (soft overlapping washes + a light paper grain).
 * Screens must keep their Scaffold/containers transparent so it shows through.
 */
@Composable
fun WatercolorTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) PagDarkColors else PagLightColors,
        typography = PagTypography
    ) {
        Box(Modifier.fillMaxSize()) {
            WatercolorBackground(dark)
            content()
        }
    }
}

@Composable
fun WatercolorBackground(dark: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val paper = if (dark) Color(0xFF14222B) else Color(0xFFFBF6EC)
    val teal = if (dark) Color(0xFF2F7F86) else Color(0xFF7FC8C4)
    val rose = if (dark) Color(0xFF8E3F5C) else Color(0xFFF2A9B5)
    val ochre = if (dark) Color(0xFF8A5A2B) else Color(0xFFF6C98B)
    val lavender = if (dark) Color(0xFF4B4690) else Color(0xFFB9B0E8)
    val sage = if (dark) Color(0xFF3C6B52) else Color(0xFFA9D3A3)
    val grain = if (dark) Color(0xFFFFFFFF) else Color(0xFF6B5B45)

    Canvas(modifier) {
        drawRect(paper)
        val w = size.width
        val h = size.height
        val a = if (dark) 0.34f else 0.45f

        fun wash(cx: Float, cy: Float, r: Float, c: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.55f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
        }
        wash(w * 0.05f, h * 0.04f, w * 0.95f, teal, a)
        wash(w * 1.00f, h * 0.14f, w * 0.80f, rose, a * 0.9f)
        wash(w * 0.92f, h * 0.55f, w * 0.85f, ochre, a * 0.7f)
        wash(w * 0.00f, h * 0.64f, w * 0.90f, lavender, a * 0.8f)
        wash(w * 0.55f, h * 1.00f, w * 1.00f, sage, a)

        // Subtle paper grain, deterministic so it never shimmers.
        val rnd = Random(7)
        repeat(260) {
            drawCircle(
                color = grain.copy(alpha = if (dark) 0.035f else 0.05f),
                radius = 0.8f + rnd.nextFloat() * 1.6f,
                center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)
            )
        }
    }
}

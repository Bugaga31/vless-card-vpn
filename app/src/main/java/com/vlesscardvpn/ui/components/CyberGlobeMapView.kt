package com.vlesscardvpn.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.ui.theme.NeonCyan
import com.vlesscardvpn.ui.theme.NeonGreen
import com.vlesscardvpn.ui.theme.NeonPurple
import com.vlesscardvpn.ui.theme.NeonRed
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CyberGlobeMapView(
    modifier: Modifier = Modifier,
    fragmentationLevel: Int = 5,
    isConnected: Boolean = false,
    speedBps: Long = 0L
) {
    val infiniteTransition = rememberInfiniteTransition(label = "GlobeMatrix")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GlobeSpin"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseGlow"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(180.dp)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val globeRadius = size.height * 0.42f

        // 1. Holographic Grid Latitude & Longitude lines
        drawCircle(
            color = NeonCyan.copy(alpha = 0.18f),
            radius = globeRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = NeonPurple.copy(alpha = 0.12f),
            radius = globeRadius * 0.65f,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.dp.toPx())
        )

        // Latitudes
        for (i in 1..3) {
            val h = globeRadius * (i * 0.28f)
            drawOval(
                color = NeonCyan.copy(alpha = 0.09f),
                topLeft = Offset(centerX - globeRadius, centerY - h),
                size = androidx.compose.ui.geometry.Size(globeRadius * 2, h * 2),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Longitudes rotation
        for (i in 0 until 6) {
            val ang = Math.toRadians((rotationAngle + i * 60).toDouble())
            val rx = globeRadius * cos(ang).toFloat()
            drawOval(
                color = NeonCyan.copy(alpha = 0.08f),
                topLeft = Offset(centerX - rx.coerceAtLeast(4f), centerY - globeRadius),
                size = androidx.compose.ui.geometry.Size((rx * 2).coerceAtLeast(8f), globeRadius * 2),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // 2. User Location Node (Origin)
        val userNode = Offset(centerX - globeRadius * 0.55f, centerY + globeRadius * 0.2f)
        drawCircle(
            brush = Brush.radialGradient(listOf(NeonCyan, Color.Transparent)),
            radius = 12.dp.toPx() * pulseGlow,
            center = userNode
        )
        drawCircle(
            color = NeonCyan,
            radius = 4.dp.toPx(),
            center = userNode
        )

        // 3. Exit Nodes (Global Targets)
        val exitNodes = listOf(
            Offset(centerX + globeRadius * 0.55f, centerY - globeRadius * 0.25f), // EU
            Offset(centerX + globeRadius * 0.35f, centerY + globeRadius * 0.45f), // Asia
            Offset(centerX - globeRadius * 0.2f, centerY - globeRadius * 0.55f)   // US
        )

        // 4. Fractal Multi-Hop Neural Routes
        val hopsToDraw = fragmentationLevel.coerceIn(1, 8)
        for (h in 0 until hopsToDraw) {
            val target = exitNodes[h % exitNodes.size]
            val midYOffset = ((h % 3) - 1) * 35.dp.toPx()
            val midXOffset = ((h % 2) - 0.5f) * 40.dp.toPx()
            val controlPoint1 = Offset(userNode.x + (target.x - userNode.x) * 0.3f + midXOffset, userNode.y - 40.dp.toPx() + midYOffset)
            val controlPoint2 = Offset(userNode.x + (target.x - userNode.x) * 0.7f - midXOffset, target.y - 30.dp.toPx() - midYOffset)

            val path = Path().apply {
                moveTo(userNode.x, userNode.y)
                cubicTo(controlPoint1.x, controlPoint1.y, controlPoint2.x, controlPoint2.y, target.x, target.y)
            }

            val routeColor = if (isConnected) {
                if (h % 2 == 0) NeonCyan.copy(alpha = 0.65f) else NeonPurple.copy(alpha = 0.65f)
            } else {
                Color.DarkGray.copy(alpha = 0.35f)
            }

            drawPath(
                path = path,
                color = routeColor,
                style = Stroke(width = 1.8.dp.toPx())
            )

            // Animated packet spark on each active route
            if (isConnected) {
                val t = (wavePhase + (h * 0.2f)) % 1f
                // Approximate cubic bezier point
                val u = 1 - t
                val px = u*u*u*userNode.x + 3*u*u*t*controlPoint1.x + 3*u*t*t*controlPoint2.x + t*t*t*target.x
                val py = u*u*u*userNode.y + 3*u*u*t*controlPoint1.y + 3*u*t*t*controlPoint2.y + t*t*t*target.y

                drawCircle(
                    color = if (h % 2 == 0) NeonGreen else NeonCyan,
                    radius = 3.5.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }

        // Draw exit points
        exitNodes.forEachIndexed { idx, exit ->
            val exitColor = if (isConnected) NeonGreen else Color.Gray
            drawCircle(
                color = exitColor.copy(alpha = 0.3f),
                radius = 8.dp.toPx() * pulseGlow,
                center = exit
            )
            drawCircle(
                color = exitColor,
                radius = 3.5.dp.toPx(),
                center = exit
            )
        }
    }
}

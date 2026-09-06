package com.vlesscardvpn.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.ui.theme.DarkBackground
import com.vlesscardvpn.ui.theme.NeonCyan
import com.vlesscardvpn.ui.theme.NeonPurple
import kotlin.random.Random

@Composable
fun CyberParticleBackground(
    modifier: Modifier = Modifier,
    particleCount: Int = 30
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ParticlesAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ParticlePhase"
    )

    val particles = remember {
        List(particleCount) {
            CyberParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 2.5f + 1f,
                speed = Random.nextFloat() * 0.4f + 0.1f,
                color = if (Random.nextBoolean()) NeonCyan.copy(alpha = Random.nextFloat() * 0.35f + 0.1f)
                        else NeonPurple.copy(alpha = Random.nextFloat() * 0.3f + 0.1f)
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        val width = size.width
        val height = size.height

        particles.forEach { p ->
            val curY = ((p.y + phase * p.speed) % 1f) * height
            val curX = p.x * width
            drawCircle(
                color = p.color,
                radius = p.radius.dp.toPx(),
                center = Offset(curX, curY)
            )
        }
    }
}

private data class CyberParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val color: Color
)

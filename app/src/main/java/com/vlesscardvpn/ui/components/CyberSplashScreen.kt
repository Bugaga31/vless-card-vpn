package com.vlesscardvpn.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.ui.theme.*

@Composable
fun CyberSplashScreen(
    onAnimationEnd: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SplashPulse")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowScale"
    )

    val neonAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "NeonAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        // Ambient background neon glow circles
        Box(
            modifier = Modifier
                .size(280.dp)
                .scale(glowScale)
                .blur(48.dp)
                .background(
                    Brush.radialGradient(
                        listOf(NeonCyan.copy(alpha = 0.25f), NeonPurple.copy(alpha = 0.15f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Cyber Badge Icon
            Surface(
                modifier = Modifier
                    .size(96.dp)
                    .scale(glowScale),
                shape = RoundedCornerShape(26.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(2.dp, Brush.linearGradient(listOf(NeonCyan, NeonPurple)))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(46.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = NeonPurple,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Title
            Text(
                text = "VLESS CARD CORE",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "NEXT-GEN REALITY & VK-RELAY ENGINE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = NeonCyan,
                    modifier = Modifier.alpha(neonAlpha)
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Fast Loading bar
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(3.dp)
                    .background(DarkBorder, RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.7f)
                        .background(
                            Brush.horizontalGradient(listOf(NeonCyan, NeonPurple)),
                            RoundedCornerShape(2.dp)
                        )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "INITIALIZING HIGH-SPEED TUNNELS...",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextTertiary
            )
        }
    }
}

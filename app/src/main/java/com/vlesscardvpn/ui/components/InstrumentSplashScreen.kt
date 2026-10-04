package com.vlesscardvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.ui.theme.InstrumentDimens
import com.vlesscardvpn.ui.theme.SignalOrange

/**
 * Precision Field Instrument Splash / Intro:
 * Clean, calm initialization screen without battery-draining continuous animations.
 */
@Composable
fun InstrumentSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )

            Spacer(modifier = Modifier.height(InstrumentDimens.space16))

            Text(
                text = "VLESS Card",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                letterSpacing = 0.sp
            )

            Spacer(modifier = Modifier.height(InstrumentDimens.space4))

            Text(
                text = "Готовим интерфейс",
                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

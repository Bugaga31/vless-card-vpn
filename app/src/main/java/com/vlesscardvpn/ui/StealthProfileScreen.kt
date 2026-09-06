package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.StealthProfileType
import com.vlesscardvpn.domain.StealthSettings
import com.vlesscardvpn.ui.theme.*

/**
 * Precision Stealth / Network Masking Profile Screen:
 * TLS Fingerprint & SNI preset selector without fantasy elements.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StealthProfileScreen(
    repo: AppRepository,
    onBack: () -> Unit = {}
) {
    var stealthSettings by remember { mutableStateOf(StealthSettings()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Профили маскировки TLS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space12),
            verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
        ) {
            Text(
                text = "ПРЕДУСТАНОВКИ МАСКИРОВКИ ЗАПРОСОВ",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StealthProfileType.values().forEach { profile ->
                val isSelected = stealthSettings.activeProfile == profile

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            stealthSettings = stealthSettings.copy(activeProfile = profile)
                            repo.updateSettings { s -> s.copy(customSniOverride = profile.defaultSni) }
                        },
                    shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                    color = if (isSelected) SignalOrangeContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) SignalOrange else MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(InstrumentDimens.space16)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = profile.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            if (isSelected) {
                                Text(
                                    text = "АКТИВЕН",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SignalOrange
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(InstrumentDimens.space4))
                        Text(
                            text = profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(InstrumentDimens.space8))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "SNI: ${profile.defaultSni}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "FP: ${profile.tlsFingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.HomePresentation

/** Opaque status surfaces: the ocean underneath cannot lower text contrast. */
@Composable
fun HomeStatusPill(text:String,verdict:HomePresentation.Verdict,modifier:Modifier=Modifier) {
    val dark=MaterialTheme.colorScheme.surface.luminance()<.5f
    val pair=when(verdict) {
        HomePresentation.Verdict.PASS->if(dark) 0xFF0D3320 to 0xFFA7F3D0 else 0xFFE6F4EA to 0xFF0D5226
        HomePresentation.Verdict.FAIL->if(dark) 0xFF3F1114 to 0xFFFECACA else 0xFFFCE8E6 to 0xFF8C1D18
        HomePresentation.Verdict.UNKNOWN,HomePresentation.Verdict.STALE->if(dark) 0xFF3B2806 to 0xFFFDE68A else 0xFFFEF7E0 to 0xFF704700
        else->if(dark) 0xFF1F2421 to 0xFFE2E8F0 else 0xFFF1F5F9 to 0xFF334155
    }
    Surface(modifier.semantics(mergeDescendants=true){},shape=RoundedCornerShape(12.dp),
        color=Color(pair.first),contentColor=Color(pair.second),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Text(text,Modifier.padding(horizontal=10.dp,vertical=8.dp),
            style=MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings="tnum")))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeServiceChips(services:List<HomePresentation.Service>) {
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        services.forEach {s->HomeStatusPill("${s.verdict.symbol} ${s.title}: ${s.verdict.label}"+
            s.detail.takeIf {it.isNotBlank()}?.let {" · $it"}.orEmpty(),s.verdict)}
    }
}

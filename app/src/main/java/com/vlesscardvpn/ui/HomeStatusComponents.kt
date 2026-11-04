package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.HomePresentation

/** Container to content colours per verdict; every pair keeps at least 4.5:1 contrast (see HomeUiWiringTest). */
@Composable
private fun verdictColors(verdict:HomePresentation.Verdict):Pair<Color,Color> {
    val dark=MaterialTheme.colorScheme.surface.luminance()<.5f
    val pair=when(verdict) {
        HomePresentation.Verdict.PASS->if(dark) 0xFF0D3320 to 0xFFA7F3D0 else 0xFFE6F4EA to 0xFF0D5226
        HomePresentation.Verdict.FAIL->if(dark) 0xFF3F1114 to 0xFFFECACA else 0xFFFCE8E6 to 0xFF8C1D18
        HomePresentation.Verdict.UNKNOWN,HomePresentation.Verdict.STALE->if(dark) 0xFF3B2806 to 0xFFFDE68A else 0xFFFEF7E0 to 0xFF704700
        else->if(dark) 0xFF1F2421 to 0xFFE2E8F0 else 0xFFF1F5F9 to 0xFF334155
    }
    return Color(pair.first) to Color(pair.second)
}

/** Opaque status surfaces: the ocean underneath cannot lower text contrast. */
@Composable
fun HomeStatusPill(text:String,verdict:HomePresentation.Verdict,modifier:Modifier=Modifier) {
    val (container,content)=verdictColors(verdict)
    Surface(modifier.semantics(mergeDescendants=true){},shape=RoundedCornerShape(12.dp),
        color=container,contentColor=content,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Text(text,Modifier.padding(horizontal=10.dp,vertical=8.dp),
            style=MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings="tnum")))
    }
}

/**
 * Service checks of the Auto profile as a compact grid of equal-height tiles (two columns).
 * Checks switched off in the profile are not shown; when nothing was measured yet a single line replaces
 * a wall of identical «Нет данных» chips. One column on narrow screens and with large fonts.
 */
@Composable
fun HomeServiceChips(services:List<HomePresentation.Service>) {
    val shown=services.filter {it.verdict!=HomePresentation.Verdict.NOT_REQUIRED}
    if(shown.isEmpty()) return
    if(shown.all {it.verdict==HomePresentation.Verdict.NO_DATA}) {
        Text("Ещё не проверялось: "+shown.joinToString(" · ") {it.title},
            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val columns=if(LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.3f) 1 else 2
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        // Rows of equal-height tiles; an odd last tile keeps the column width instead of stretching.
        shown.chunked(columns).forEach {row->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach {s->HomeServiceTile(s,Modifier.weight(1f).fillMaxHeight())}
                repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
            }
        }
    }
}

@Composable
private fun HomeServiceTile(s:HomePresentation.Service,modifier:Modifier=Modifier) {
    val (container,content)=verdictColors(s.verdict)
    Surface(modifier.semantics(mergeDescendants=true){},shape=RoundedCornerShape(14.dp),
        color=container,contentColor=content,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(s.title,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
            Text("${s.verdict.symbol} ${s.verdict.label}"+s.detail.takeIf {it.isNotBlank()}?.let {" · $it"}.orEmpty(),
                style=MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings="tnum")))
        }
    }
}

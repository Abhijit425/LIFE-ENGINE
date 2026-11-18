package com.abhijit.lifeengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Glass = Color(0xD90C1216)
    val GlassLight = Color(0x99131B21)
    val Stroke = Color(0x33FFFFFF)
    val Amber = Color(0xFFF3A65A)
    val Teal = Color(0xFF5AC8B8)
    val Text = Color(0xFFECEFF1)
    val Muted = Color(0xFF9AA7AF)
    val Danger = Color(0xFFE57373)
}

@Composable
fun LifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Amber, secondary = Palette.Teal, background = Color(0xFF0B0F12),
            surface = Color(0xFF11181D), onPrimary = Color(0xFF1B1209), onSurface = Palette.Text,
        ),
        content = content,
    )
}

/** Translucent rounded card used for every floating panel. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Glass)
            .border(1.dp, Palette.Stroke, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when {
        selected -> Palette.Amber
        else -> Palette.GlassLight
    }
    val fg = when {
        !enabled -> Palette.Muted.copy(alpha = 0.5f)
        selected -> Color(0xFF1B1209)
        else -> Palette.Text
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else Palette.Stroke, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        if (subtitle != null) Text(subtitle, color = Palette.Muted, fontSize = 13.sp)
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Palette.Muted, fontSize = 13.sp)
        Text(value, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PhaseNote(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x223AC8B8))
            .border(1.dp, Color(0x445AC8B8), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) { Text(text, color = Palette.Teal, fontSize = 12.5.sp, lineHeight = 17.sp) }
}

package dev.skillbill.tray.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DesignLabel(text: String, modifier: Modifier = Modifier) {
  Text(text.uppercase(), modifier, color = dim, fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, lineHeight = 9.5.sp, letterSpacing = 1.33.sp)
}

@Composable
fun DesignButton(text: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Box(modifier.height(32.dp).background(raised, RoundedCornerShape(8.dp)).border(1.dp, if (danger) red.copy(alpha = .45f) else strongBorder, RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
    Text(text, color = (if (danger) red else ink).copy(alpha = if (enabled) 1f else .42f), fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1)
  }
}

@Composable
fun DesignKv(label: String, value: String, valueColor: Color = ink) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    Text(label, Modifier.weight(.8f), color = muted, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.1.sp)
    Text(value, Modifier.weight(1.2f), color = valueColor, fontFamily = mono, fontSize = 11.5.sp, lineHeight = 16.1.sp, textAlign = TextAlign.End)
  }
}

package dev.skillbill.tray.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.domain.Lifecycle

@Composable
internal fun DesignLabel(text: String, modifier: Modifier = Modifier) {
  Text(text.uppercase(), modifier, color = dim, fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, lineHeight = 9.5.sp, letterSpacing = 1.33.sp)
}

@Composable
internal fun DesignButton(text: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Box(modifier.height(32.dp).background(raised, RoundedCornerShape(8.dp)).border(1.dp, if (danger) red.copy(alpha = .45f) else strongBorder, RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
    Text(text, color = (if (danger) red else ink).copy(alpha = if (enabled) 1f else .42f), fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1)
  }
}

@Composable
internal fun DesignKv(label: String, value: String, valueColor: Color = ink) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    Text(label, Modifier.weight(.8f), color = muted, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.1.sp)
    Text(value, Modifier.weight(1.2f), color = valueColor, fontFamily = mono, fontSize = 11.5.sp, lineHeight = 16.1.sp, textAlign = TextAlign.End)
  }
}

@Composable
internal fun WorkDot(lifecycle: Lifecycle, modifier: Modifier = Modifier) {
  Canvas(modifier.size(if (lifecycle == Lifecycle.BLOCKED) 8.dp else 7.dp)) {
    val color = lifecycleColor(lifecycle)
    if (lifecycle == Lifecycle.PAUSED || lifecycle == Lifecycle.BLOCKED || lifecycle == Lifecycle.IDLE) {
      val stroke = (if (lifecycle == Lifecycle.BLOCKED) 2.dp else 1.5.dp).toPx()
      drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
    } else drawCircle(color)
  }
}

package dev.skillbill.desktop.core.ui.diff

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.ui.changes.changesMono

internal val LocalDiffHorizontalScroll = compositionLocalOf<ScrollState?> { null }

/** Keeps the vertical list at viewport width while a virtual horizontal extent supplies its scroll range. */
@Composable
fun DiffHorizontalScroll(longestChars: Int, modifier: Modifier = Modifier, scrollState: ScrollState = rememberScrollState(), content: @Composable () -> Unit) {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val mono = changesMono(12f, FontWeight.Normal, SkillBillTheme.colors.fg2, 20f)
  val charWidth = remember(measurer, mono, density) { with(density) { measurer.measure("0", mono).size.width.toDp() } }
  BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
    val viewportWidth = maxWidth
    val extent = maxOf(viewportWidth, 128.dp + charWidth * longestChars)
    Box(Modifier.horizontalScroll(scrollState).width(extent).fillMaxHeight()) {
      Box(Modifier.width(viewportWidth).fillMaxHeight().offset { IntOffset(scrollState.value, 0) }) {
        CompositionLocalProvider(LocalDiffHorizontalScroll provides scrollState, content = content)
      }
    }
  }
}

@Composable
internal fun Modifier.diffHorizontalOffset(): Modifier {
  val scroll = LocalDiffHorizontalScroll.current
  return offset { IntOffset(-(scroll?.value ?: 0), 0) }
}

package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion

/** One line that scrolls sideways while it overflows; with reduced motion it stays still and ends in an ellipsis. */
@Composable
fun SbMarqueeText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
  val reducedMotion = LocalReducedMotion.current
  BasicText(
    text,
    modifier = if (reducedMotion) modifier else modifier.basicMarquee(iterations = Int.MAX_VALUE),
    style = style,
    overflow = if (reducedMotion) TextOverflow.Ellipsis else TextOverflow.Clip,
    softWrap = false,
    maxLines = 1,
  )
}

@Composable
fun SbMarqueeText(text: String, style: TextStyle, modifier: Modifier = Modifier) = SbMarqueeText(AnnotatedString(text), style, modifier)

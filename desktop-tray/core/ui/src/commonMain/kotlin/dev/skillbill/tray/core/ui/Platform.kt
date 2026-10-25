package dev.skillbill.tray.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.muted
import kotlin.time.Instant

// Platform facts the common feature code cannot reach itself: the local time zone, the home directory, the clipboard and the
// desktop-only scrollbar and text dialogs. Each has one actual per target.

/** Formats [value] in the user's time zone with a `java.time`-style [pattern] such as `d MMM HH:mm`. */
expect fun formatInstant(value: Instant, pattern: String): String

/** The instant the local calendar day holding [value] began, in the system time zone. */
expect fun startOfLocalDay(value: Instant): Instant

/** Replaces the user's home directory prefix with `~` so a path reads short. */
expect fun abbreviateHomeDirectory(path: String): String

/** A function that puts text on the system clipboard. */
@Composable
expect fun rememberCopyToClipboard(): (String) -> Unit

/** A truncated reason with a "Show more" button that opens its full scrollable text, titled [title], in a dialog. */
@Composable
expect fun ExpandableReason(
  reason: String,
  title: String,
  modifier: Modifier = Modifier,
  color: Color = muted,
  fontSize: TextUnit = 12.sp,
  lineHeight: TextUnit = 18.sp,
)

/** A vertical scrollbar for [state]; the caller aligns it, for example with `Modifier.align(Alignment.CenterEnd)`. */
@Composable
expect fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier)

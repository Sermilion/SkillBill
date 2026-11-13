package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline

/**
 * Shared expandable result row: caller-owned ref, optional outside leading/end slots, folded title,
 * state badge, optional chevron toggle, and disclosure body when expanded.
 */
@Composable
fun ExpandableResultRow(
  modifier: Modifier = Modifier,
  topDivider: Boolean = false,
  open: Boolean = false,
  onToggle: (() -> Unit)? = null,
  toggleLabel: String = "Toggle row",
  leadingOutside: (@Composable () -> Unit)? = null,
  endOutside: (@Composable () -> Unit)? = null,
  ref: (@Composable () -> Unit)? = null,
  title: @Composable () -> Unit,
  stateBadge: (@Composable () -> Unit)? = null,
  disclosure: (@Composable () -> Unit)? = null,
  disclosureInsetStart: Dp? = null,
) {
  val colors = SkillBillTheme.colors
  val currentToggle = rememberUpdatedState(onToggle)
  val body = disclosure
  val expandable = onToggle != null && body != null
  val toggleModifier = if (onToggle != null && body != null) {
    Modifier
      .clickable(
        role = Role.Button,
        onClickLabel = toggleLabel,
        onClick = { currentToggle.value?.invoke() },
      )
      .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
  } else Modifier
  val insetStart = disclosureInsetStart ?: when {
    leadingOutside != null -> 62.dp
    ref != null -> 44.dp
    else -> 12.dp
  }
  Column(
    modifier
      .fillMaxWidth()
      .then(if (topDivider) Modifier.hairline(Edge.Top, colors.borderSubtle) else Modifier),
  ) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val stackEndOutside = endOutside != null && maxWidth < 480.dp
      if (stackEndOutside) {
        Column(Modifier.fillMaxWidth()) {
          resultHeader(
            leadingOutside, Modifier, toggleModifier, ref, title, stateBadge, expandable, open,
          )
          endOutside?.invoke()
        }
      } else {
        Row(Modifier.fillMaxWidth()) {
          resultHeader(
            leadingOutside, Modifier.weight(1f), toggleModifier, ref, title, stateBadge, expandable, open,
          )
          if (endOutside != null) {
            Column(Modifier.width(104.dp).padding(top = 8.dp, end = 10.dp), horizontalAlignment = Alignment.End) {
              endOutside()
            }
          }
        }
      }
    }
    if (open && body != null) {
      Column(
        Modifier.padding(start = insetStart, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        body()
      }
    }
  }
}

@Composable
private fun resultHeader(
  leadingOutside: (@Composable () -> Unit)?,
  rowWeight: Modifier,
  toggleModifier: Modifier,
  ref: (@Composable () -> Unit)?,
  title: @Composable () -> Unit,
  stateBadge: (@Composable () -> Unit)?,
  expandable: Boolean,
  open: Boolean,
) {
  Row(Modifier.fillMaxWidth().then(rowWeight)) {
    if (leadingOutside != null) {
      Column(Modifier.width(30.dp).padding(start = 12.dp, top = 11.dp)) { leadingOutside() }
    }
    Row(
      Modifier
        .weight(1f)
        .then(toggleModifier)
        .padding(
          start = if (leadingOutside != null) 2.dp else 12.dp,
          end = 12.dp,
          top = 9.dp,
          bottom = 9.dp,
        ),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      ref?.invoke()
      Column(Modifier.weight(1f, fill = true)) { title() }
      Row(
        Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        stateBadge?.invoke()
        if (expandable) ResultRowChevron(open)
      }
    }
  }
}

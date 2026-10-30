package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.section as lazySection

/** Shared lazy result sections, with independent collapse state and assessment ordering. */
internal class DetailSections(
  private val scope: LazyListScope,
  val collapse: SectionCollapse,
  val assessmentOrder: MutableState<AssessmentOrder> = mutableStateOf(AssessmentOrder.Default),
) : LazyListScope by scope {
  fun section(
    key: Any,
    title: String,
    count: String? = null,
    lead: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    spaceBefore: Dp = DocBlockGap,
    collapsedByDefault: Boolean = false,
    content: LazyListScope.() -> Unit,
  ) = lazySection(key, title, count, lead, actions, spaceBefore, collapse, collapsedByDefault, content)
}

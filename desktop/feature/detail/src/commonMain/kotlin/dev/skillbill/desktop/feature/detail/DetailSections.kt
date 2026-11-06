package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.section as lazySection
import dev.skillbill.desktop.core.designsystem.SkillBillSpace
import androidx.compose.ui.Modifier

internal class LazyKeyIndex {
  private val indexes = HashMap<Any, Int>()
  private var next = 0

  fun reset() { indexes.clear(); next = 0 }

  fun record(key: Any?) {
    if (key != null) indexes[key] = next
    next++
  }

  fun recordAll(count: Int, key: ((Int) -> Any)?) {
    repeat(count) { index -> if (key != null) indexes[key(index)] = next + index }
    next += count
  }

  fun indexOf(key: Any): Int? = indexes[key]
}

/** Shared lazy result sections, with independent collapse state and assessment ordering. */
internal class DetailSections(
  private val scope: LazyListScope,
  val collapse: SectionCollapse,
  val scrollState: LazyListState,
  val assessmentOrder: MutableState<AssessmentOrder> = mutableStateOf(AssessmentOrder.Default),
  private val keys: LazyKeyIndex = LazyKeyIndex(),
  val planScreenState: PlanScreenState? = null,
) : LazyListScope by scope {
  init { keys.reset() }

  override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
    keys.record(key)
    scope.item(key, contentType, content)
  }

  override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) {
    keys.recordAll(count, key)
    scope.items(count, key, contentType, itemContent)
  }

  override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.(index: Int) -> Unit) {
    keys.record(key)
    scope.stickyHeader(key, contentType, content)
  }
  fun section(
    key: Any,
    title: String,
    count: String? = null,
    lead: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    spaceBefore: Dp = DocBlockGap,
    collapsedByDefault: Boolean = false,
    countStyle: (@Composable () -> TextStyle)? = null,
    content: LazyListScope.() -> Unit,
  ) = lazySection(key, title, count, lead, actions, spaceBefore, collapse, collapsedByDefault, countStyle, content)

  fun planSection(
    key: String,
    title: String,
    count: String? = null,
    tools: (@Composable RowScope.() -> Unit)? = null,
    collapsedByDefault: Boolean = false,
    content: LazyListScope.() -> Unit,
  ) {
    item(key = "$key-gap") { Spacer(Modifier.height(SkillBillSpace.s5)) }
    val open = !collapse.isCollapsed(key, collapsedByDefault)
    item(key = key) {
      Column {
        PlanSectionHeader(title, count, open, { this@DetailSections.collapse.toggle(key, collapsedByDefault) }, tools)
        if (open) Spacer(Modifier.height(planSectionBodyGap))
      }
    }
    if (open) content()
  }

  fun indexOf(key: Any): Int? = keys.indexOf(key)
}

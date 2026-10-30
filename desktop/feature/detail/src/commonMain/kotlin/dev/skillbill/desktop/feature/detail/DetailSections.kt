package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.section as lazySection

/**
 * Where each keyed item of the detail document sits, refreshed every time the list's content runs, so a pane pointer can scroll
 * the document to a section by its key; `LazyListState` scrolls by index only.
 */
internal class DocumentIndex {
  private var positions: Map<Any, Int> = emptyMap()

  fun indexOf(key: Any): Int? = positions[key]

  fun replace(next: Map<Any, Int>) {
    positions = next
  }
}

/**
 * The detail document's lazy scope with the screen's [SectionCollapse], so every section header can collapse its body. It counts
 * the items it adds and records each key's index in [keyPositions].
 */
internal class DetailSections(
  private val scope: LazyListScope,
  val collapse: SectionCollapse,
  val assessmentOrder: MutableState<AssessmentOrder> = mutableStateOf(AssessmentOrder.Default),
) : LazyListScope by scope {
  private var added = 0
  private val positions = mutableMapOf<Any, Int>()

  val keyPositions: Map<Any, Int> get() = positions

  override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
    if (key != null) positions[key] = added
    added += 1
    scope.item(key, contentType, content)
  }

  override fun items(
    count: Int,
    key: ((index: Int) -> Any)?,
    contentType: (index: Int) -> Any?,
    itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
  ) {
    if (key != null) repeat(count) { positions[key(it)] = added + it }
    added += count
    scope.items(count, key, contentType, itemContent)
  }

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

package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class DetailSectionsTest {
  @Test
  fun `key index follows lazy items and nested section content when collapsed`() = runComposeUiTest {
    val keys = LazyKeyIndex()
    lateinit var listState: androidx.compose.foundation.lazy.LazyListState
    lateinit var collapse: dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
    setContent {
      SkillBillTheme(reducedMotion = true) {
        collapse = rememberSectionCollapse()
        listState = rememberLazyListState()
        LazyColumn(state = listState) {
          val sections = DetailSections(this, collapse, keys = keys)
          sections.item(key = "top") { androidx.compose.foundation.text.BasicText("top") }
          sections.section("nested-section", "Nested section", spaceBefore = 0.dp) {
            item(key = "nested-item") { androidx.compose.foundation.text.BasicText("nested item") }
            items(2, key = { index -> "nested-$index" }) { index -> androidx.compose.foundation.text.BasicText("nested $index") }
          }
          sections.item { androidx.compose.foundation.text.BasicText("unkeyed") }
          sections.item(key = "last") { androidx.compose.foundation.text.BasicText("last") }
        }
      }
    }

    waitForIdle()
    runOnIdle {
      listState.layoutInfo.visibleItemsInfo.forEach { info ->
        val key = info.key
        if (key is String && key != "unkeyed") assertEquals(info.index, keys.indexOf(key))
      }
      assertNotNull(keys.indexOf("last"))
    }
    val oldLast = keys.indexOf("last")!!
    onNodeWithText("Nested section").performClick()
    waitForIdle()
    runOnIdle {
      assertNull(keys.indexOf("nested-item"))
      assertNull(keys.indexOf("nested-0"))
      assertEquals(oldLast - 3, keys.indexOf("last"))
    }
  }
}

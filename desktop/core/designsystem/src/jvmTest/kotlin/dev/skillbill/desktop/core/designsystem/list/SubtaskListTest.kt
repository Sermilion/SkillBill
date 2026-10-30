package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SubtaskListTest {
  @Test
  fun `the first pointer click selects a visible row without scrolling the tall list`() = runComposeUiTest {
    val selected = mutableStateOf(-1)
    var opened = -1
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.width(500.dp).height(240.dp)) {
          LazyColumn {
            item {
              SubtaskList("Subtasks", { selected.value += it }, { opened = selected.value }) {
                repeat(20) { index ->
                  SubtaskRow(index.toString(), StatusKind.Pending, null, "Subtask $index", selected = selected.value == index, onClick = { selected.value = index })
                }
              }
            }
          }
        }
      }
    }
    val row = onNodeWithText("Subtask 12").performScrollTo().assertIsDisplayed()
    val before = row.fetchSemanticsNode().boundsInRoot
    onRoot().performMouseInput { moveTo(before.center); press() }
    waitForIdle()
    onRoot().performMouseInput { release() }
    waitForIdle()
    runOnIdle { assertEquals(12, selected.value) }
    assertEquals(before.top, row.fetchSemanticsNode().boundsInRoot.top)
    row.assertIsFocused().performKeyInput {
      keyDown(Key.DirectionDown)
      keyUp(Key.DirectionDown)
      keyDown(Key.Enter)
      keyUp(Key.Enter)
    }
    runOnIdle {
      assertEquals(13, selected.value)
      assertEquals(13, opened)
    }
  }
}

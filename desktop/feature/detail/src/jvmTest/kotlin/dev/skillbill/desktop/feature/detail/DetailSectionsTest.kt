package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.ValidationCheck
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailSectionsTest {
  @Test
  fun `standalone validation uses its document while review keeps the generic metrics`() = runComposeUiTest {
    val validation = workItem("validation", lifecycle = dev.skillbill.desktop.core.domain.model.Lifecycle.Terminal).copy(
      kind = WorkKind.Phase,
      stepLabel = "validation",
      control = workItem("validation").control.copy(phaseId = "validation"),
    )
    val review = validation.copy(id = "review", title = "Review", stepLabel = "review", control = validation.control.copy(phaseId = "review"))
    assertTrue(isStandaloneValidation(validation))
    assertFalse(isStandaloneValidation(review))
    lateinit var validationList: androidx.compose.foundation.lazy.LazyListState
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val collapse = rememberSectionCollapse()
        validationList = rememberLazyListState()
        val state = DetailUiState(validation, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch)
        val page = ValidationPageState(null, mutableStateListOf(), {})
        LazyColumn(state = validationList) {
          val sections = DetailSections(this, collapse, validationList)
          sections.document(state, runActionsView(state), false, {}, null, {}, page)
        }
      }
    }
    waitForIdle()
    runOnIdle { assertTrue(validationList.layoutInfo.visibleItemsInfo.any { it.key == "validation-card" }) }
    onNodeWithText("Step").assertDoesNotExist()
    onNodeWithText("Phase elapsed").assertDoesNotExist()

    setContent {
      SkillBillTheme(reducedMotion = true) {
        val collapse = rememberSectionCollapse()
        val list = rememberLazyListState()
        val state = DetailUiState(review, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch)
        LazyColumn(state = list) {
          val sections = DetailSections(this, collapse, list)
          sections.document(state, runActionsView(state), false, {}, null, {})
        }
      }
    }
    waitForIdle()
    onNodeWithText("Step").assertExists()
  }

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
          val sections = DetailSections(this, collapse, listState, keys = keys)
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

  @Test
  fun `checks section requires availability and waits on an active empty run`() = runComposeUiTest {
    val item = workItem("validation", kind = WorkKind.Phase).copy(
      stepLabel = "validation",
      control = workItem("validation", kind = WorkKind.Phase).control.copy(invocationId = "invocation", phaseId = "validation"),
    )
    val check = ValidationCheck("c", "compile", "./gradlew check", ".", emptyList())
    val run = ExecutionInstance(
      "invocation", "execution", "active", epoch, epoch, null, null, null, null, null, null,
      liveChecks = false, validationChecks = listOf(check),
    )
    val displayedRun = mutableStateOf(run)
    lateinit var list: androidx.compose.foundation.lazy.LazyListState
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val collapse = rememberSectionCollapse()
        list = rememberLazyListState()
        LazyColumn(state = list) {
          DetailSections(this, collapse, list).validationChecksSection(
            item, emptyDetail.copy(selectedExecution = displayedRun.value),
            DetailUiState(item, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch), {},
          )
        }
      }
    }
    waitForIdle()
    onNodeWithText("Checks").assertDoesNotExist()

    runOnIdle { displayedRun.value = run.copy(liveChecks = true) }
    waitForIdle()
    onNodeWithText("compile").assertExists()

    runOnIdle { displayedRun.value = run.copy(liveChecks = true, validationChecks = emptyList()) }
    waitForIdle()
    onNodeWithText("Waiting for the agent to list its checks.").assertExists()
  }
}

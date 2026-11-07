package dev.skillbill.desktop.feature.specs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.SpecCounts
import dev.skillbill.desktop.core.domain.model.SpecFileKind
import dev.skillbill.desktop.core.domain.model.SpecGroup
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecStatus
import dev.skillbill.desktop.core.domain.model.SpecTree
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SpecsTreeKeyboardTest {
  @Test
  fun `modified keys cannot activate select or navigate any row but plain keys and context menu still work`() = runComposeUiTest {
    val actions = mutableListOf<SpecsAction>()
    val project = SpecProject("project", "Project", "/specs", "/repo")
    val file = SpecNode.File("spec.md", "bundle/spec.md", "/specs/bundle/spec.md", SpecFileKind.ParentSpec, null, null, null)
    val bundle = SpecNode.Bundle("bundle", "bundle", "/specs/bundle", "SKILL-503", "Bundle row", SpecStatus.Pending, SpecGroup.Pending, 0, 1, listOf(file))
    val folder = SpecNode.Folder("Folder row", "folder", "/specs/folder", listOf(bundle), 1, mapOf(SpecStatus.Pending to 1))
    val tree = SpecTree(project, listOf(folder), SpecCounts(1, 0, 1, 0), false)
    val state = SpecsUiState(
      project = project,
      tree = SpecTreeState.Loaded(tree, "/specs"),
      rows = listOf(
        SpecRow(folder, 0, hasChildren = true, expanded = true),
        SpecRow(bundle, 1, hasChildren = true, expanded = true),
        SpecRow(file, 2, hasChildren = false, expanded = false),
      ),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          SpecsTree(state, { actions += it }, {}, {})
        }
      }
    }

    val bundleRow = onNodeWithText("Bundle row").performClick().assertIsFocused()
    actions.clear()
    bundleRow.performKeyInput {
      withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) }
      withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) }
      withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) }
      withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionUp) }
      withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) }
      withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) }
      withKeyDown(Key.ShiftLeft) { pressKey(Key.Spacebar) }
    }
    runOnIdle { assertEquals(emptyList(), actions) }
    bundleRow.performKeyInput { pressKey(Key.Enter) }
    runOnIdle { assertEquals(listOf<SpecsAction>(SpecsAction.Activate(bundle.path)), actions) }
    bundleRow.performKeyInput { pressKey(Key.Spacebar) }
    runOnIdle { assertEquals(listOf<SpecsAction>(SpecsAction.Activate(bundle.path), SpecsAction.Select(bundle.path)), actions) }

    val fileRow = onNodeWithText("spec.md").performClick().assertIsFocused()
    actions.clear()
    fileRow.performKeyInput {
      withKeyDown(Key.CtrlLeft) { pressKey(Key.NumPadEnter) }
      withKeyDown(Key.MetaLeft) { pressKey(Key.NumPadEnter) }
    }
    runOnIdle { assertEquals(emptyList(), actions) }
    fileRow.performKeyInput { pressKey(Key.NumPadEnter) }
    runOnIdle { assertEquals(listOf<SpecsAction>(SpecsAction.Activate(file.path)), actions) }

    val folderRow = onNodeWithText("Folder row").performClick().assertIsFocused()
    actions.clear()
    folderRow.performKeyInput {
      withKeyDown(Key.AltLeft) { pressKey(Key.DirectionRight) }
      withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) }
      withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionUp) }
      withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) }
      withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) }
    }
    runOnIdle { assertEquals(emptyList(), actions) }
    folderRow.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
    runOnIdle { assertEquals(emptyList(), actions) }
    folderRow.performKeyInput { pressKey(Key.DirectionLeft) }
    runOnIdle { assertEquals(listOf<SpecsAction>(SpecsAction.Collapse(folder.path)), actions) }
    folderRow.performKeyInput { pressKey(Key.Enter) }
    runOnIdle { assertEquals(listOf<SpecsAction>(SpecsAction.Collapse(folder.path), SpecsAction.Activate(folder.path)), actions) }
  }

  @Test
  fun `Shift F10 still opens the bundle context menu`() = runComposeUiTest {
    val actions = mutableListOf<SpecsAction>()
    val project = SpecProject("project", "Project", "/specs", "/repo")
    val bundle = SpecNode.Bundle("bundle", "bundle", "/specs/bundle", "SKILL-503", "Bundle row", SpecStatus.Pending, SpecGroup.Pending, 0, 0, emptyList())
    val tree = SpecTree(project, listOf(bundle), SpecCounts(1, 0, 1, 0), false)
    val state = SpecsUiState(
      project = project,
      tree = SpecTreeState.Loaded(tree, "/specs"),
      rows = listOf(SpecRow(bundle, 0, hasChildren = false, expanded = false)),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          SpecsTree(state, { actions += it }, {}, {})
        }
      }
    }

    onNodeWithText("Bundle row").performClick().performKeyInput {
      withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) }
      pressKey(Key.Menu)
    }
    runOnIdle { assertEquals(listOf<SpecsAction>(SpecsAction.Click(bundle.path), SpecsAction.OpenRowMenu(bundle.path), SpecsAction.OpenRowMenu(bundle.path)), actions) }
  }
}

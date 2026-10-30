package dev.skillbill.desktop.feature.config

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ConfigAgent
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigPageInteractionTest {
  @Test
  @OptIn(ExperimentalTestApi::class)
  fun `model agent tabs disable unsupported agents and select a supported agent`() = runComposeUiTest {
    val selected = mutableStateOf("claude")
    val agents = listOf(ConfigAgent("junie", modelCapable = false), ConfigAgent("cursor", modelCapable = true))
    setContent {
      SkillBillTheme(reducedMotion = false) {
        ModelAgentTabs(agents, selected.value, enabled = true) { selected.value = it }
      }
    }

    onNodeWithContentDescription("junie").assertIsNotEnabled()
    onNodeWithContentDescription("cursor").performClick()

    runOnIdle { assertEquals("cursor", selected.value) }
  }

  @Test
  @OptIn(ExperimentalTestApi::class)
  fun `keep editing action reaches the selected row rebase`() = runComposeUiTest {
    val session = ConfigPageSession("models", "claude")
    val rowKey = "build"
    val oldBasis = ConfigDraftBasis("/config/old.yaml", null, setOf("/config/old.yaml"), null)
    val newBasis = ConfigDraftBasis("/config/new.yaml", ConfigFingerprint("fresh"), setOf("/config/new.yaml"), null)
    val retained = mapOf("tier" to "IMPLEMENTATION", "model" to "typed-model", "effort" to "medium")
    val rebased = mutableStateOf<Map<String, String>?>(null)

    setContent {
      SkillBillTheme(reducedMotion = false) {
        ConfigConflictActions(
          enabled = true,
          onReload = {},
          onKeepEditing = {
            rebased.value = session.adoptRowBasis(
              rowKey,
              mapOf("tier" to "IMPLEMENTATION", "model" to "disk-model", "effort" to "disk-effort"),
              newBasis,
              keepDraft = true,
              freshInheritedFields = mapOf("model" to "fresh-model", "effort" to "high"),
            )
          },
        )
      }
    }
    session.openRows.value = setOf(rowKey)
    session.rowDrafts.value = mapOf(rowKey to retained)
    session.rowBases.value = mapOf(rowKey to oldBasis)
    session.modelDraftProvenance.value = mapOf(
      rowKey to ModelRowDraftProvenance(oldBasis, editedFields = setOf("model"), openedWithExplicitOverride = false),
    )
    session.conflictedRows.value = setOf(rowKey)

    onAllNodesWithText("Keep editing")[0].performClick()

    runOnIdle {
      assertEquals(
        mapOf("tier" to "IMPLEMENTATION", "model" to "typed-model", "effort" to "high"),
        rebased.value,
      )
    }
  }
}

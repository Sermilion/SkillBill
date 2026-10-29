package dev.skillbill.desktop.feature.config

import dev.skillbill.desktop.core.domain.model.ConfigAgent
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigPageStateTest {
  @Test
  fun keepEditingRebasesOnlyTheSelectedConflictedRow() {
    val session = ConfigPageSession("models", "claude")
    val oldBasis = machineBasis("/home/user/.config/skill-bill/config.json", "old")
    val newBasis = machineBasis("/home/user/.skill-bill/config.json", "new")
    val edited = mapOf("model" to "typed-model", "effort" to "high")
    val otherDraft = mapOf("model" to "other-model", "effort" to "low")
    session.openRows.value = setOf("review", "audit")
    session.rowDrafts.value = mapOf("review" to edited, "audit" to otherDraft)
    session.rowBases.value = mapOf("review" to oldBasis, "audit" to oldBasis)
    session.conflictedRows.value = setOf("review", "audit")

    val retained = session.adoptRowBasis("review", mapOf("model" to "fresh-model"), newBasis, keepDraft = true)

    assertEquals(edited, retained)
    assertEquals(newBasis, session.rowBases.value["review"])
    assertEquals(oldBasis, session.rowBases.value["audit"])
    assertEquals(setOf("audit"), session.conflictedRows.value)
    assertEquals(otherDraft, session.rowDrafts.value["audit"])
  }

  @Test
  fun reloadReplacesOnlyTheSelectedRowDraft() {
    val session = ConfigPageSession("models", "claude")
    val oldBasis = machineBasis("/home/user/.config/skill-bill/config.json", "old")
    val newBasis = machineBasis("/home/user/.skill-bill/config.json", "new")
    session.openRows.value = setOf("review", "audit")
    session.rowDrafts.value = mapOf(
      "review" to mapOf("model" to "typed-model"),
      "audit" to mapOf("model" to "other-model"),
    )
    session.rowBases.value = mapOf("review" to oldBasis, "audit" to oldBasis)
    session.conflictedRows.value = setOf("review", "audit")
    val freshFields = mapOf("model" to "fresh-model")

    val reloaded = session.adoptRowBasis("review", freshFields, newBasis, keepDraft = false)

    assertEquals(freshFields, reloaded)
    assertEquals(freshFields, session.rowDrafts.value["review"])
    assertEquals("other-model", session.rowDrafts.value.getValue("audit").getValue("model"))
    assertEquals(setOf("audit"), session.conflictedRows.value)
  }

  @Test
  fun pathTransitionsRequireTheRuntimeReadToAuthorizeBothProjectPaths() {
    val legacy = "/repo/.skill-bill/config.yaml"
    val store = "/home/user/.skill-bill/projects/project-id/config.yaml"
    val oldBasis = ConfigDraftBasis(legacy, ConfigFingerprint("same"), setOf(legacy, store), store)
    val storeBasis = ConfigDraftBasis(store, ConfigFingerprint("same"), setOf(legacy, store), store)
    val unrelated = ConfigDraftBasis("/tmp/other/config.yaml", ConfigFingerprint("same"), setOf(legacy, store), store)
    val otherProject = ConfigDraftBasis("/tmp/project/config.yaml", ConfigFingerprint("same"), setOf("/tmp/project/config.yaml"), "/tmp/project/config.yaml")

    assertTrue(configPathTransitionAllowed(oldBasis, storeBasis))
    assertFalse(configPathTransitionAllowed(oldBasis, unrelated))
    assertFalse(configPathTransitionAllowed(oldBasis, otherProject))
  }

  @Test
  fun junieCannotBeSelectedForModelDirectives() {
    assertFalse(modelAgentSelectable(ConfigAgent("junie", modelCapable = false)))
    assertTrue(modelAgentSelectable(ConfigAgent("cursor", modelCapable = true)))
  }

  @Test
  fun commentConfirmationIsScopedToTheConfirmedFilePath() {
    val ledger = CommentWarningLedger()

    assertFalse(ledger.confirmed("/project-a/config.yaml"))
    ledger.confirm("/project-a/config.yaml")

    assertTrue(ledger.confirmed("/project-a/config.yaml"))
    assertFalse(ledger.confirmed("/project-b/config.yaml"))
  }

  private fun machineBasis(path: String, fingerprint: String) =
    ConfigDraftBasis(
      path = path,
      fingerprint = ConfigFingerprint(fingerprint),
      candidatePaths = setOf("/home/user/.config/skill-bill/config.json", "/home/user/.skill-bill/config.json"),
      projectStorePath = null,
    )
}

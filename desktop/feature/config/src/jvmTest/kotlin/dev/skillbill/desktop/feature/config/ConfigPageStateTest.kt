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
  fun keepEditingRebasesInheritedModelFieldsAndRetainsOnlyTheEditedOverride() {
    val session = ConfigPageSession("models", "claude")
    val oldBasis = machineBasis("/home/user/.config/skill-bill/config.json", "old")
    val newBasis = machineBasis("/home/user/.skill-bill/config.json", "new")
    val rowKey = "plan"
    val draft = mapOf("tier" to "reasoning", "model" to "typed-model", "effort" to "medium")
    val inherited = mapOf("model" to "fresh-model", "effort" to "high")
    session.openRows.value = setOf(rowKey)
    session.rowDrafts.value = mapOf(rowKey to draft)
    session.rowBases.value = mapOf(rowKey to oldBasis)
    session.modelDraftProvenance.value = mapOf(
      rowKey to ModelRowDraftProvenance(oldBasis, editedFields = setOf("model"), openedWithExplicitOverride = false),
    )
    session.conflictedRows.value = setOf(rowKey)

    val retained = session.adoptRowBasis(
      rowKey,
      mapOf("tier" to "reasoning", "model" to "disk-model", "effort" to "disk-effort"),
      newBasis,
      keepDraft = true,
      freshInheritedFields = inherited,
    )

    assertEquals(mapOf("tier" to "reasoning", "model" to "typed-model", "effort" to "high"), retained)
    assertEquals(newBasis, session.modelDraftProvenance.value.getValue(rowKey).basis)
    assertEquals(setOf("model"), session.modelDraftProvenance.value.getValue(rowKey).editedFields)
    assertTrue(session.conflictedRows.value.isEmpty())
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
    val agents = listOf(ConfigAgent("junie", modelCapable = false), ConfigAgent("cursor", modelCapable = true))

    assertFalse(modelAgentSelectable(agents.first()))
    assertTrue(modelAgentSelectable(agents.last()))
    assertEquals(null, modelAgentSelection("claude", "junie", agents, enabled = true))
    assertEquals("cursor", modelAgentSelection("claude", "cursor", agents, enabled = true))
  }

  @Test
  fun commentConfirmationIsScopedToTheConfirmedFilePath() {
    val ledger = CommentWarningLedger()
    val session = ConfigPageSession("project", "claude")
    val fingerprint = ConfigFingerprint("revision-a")
    val draft = mapOf("review_context_budget" to "5000")

    assertFalse(ledger.confirmed("/project-a/config.yaml"))
    assertTrue(
      requestCommentConfirmation(
        session, "project", true, ledger, "/project-a/config.yaml", false, "project-a", 7, "review-context", fingerprint, draft,
      ),
    )
    assertTrue(session.warning.value)
    assertEquals(
      CommentConfirmation("/project-a/config.yaml", "project-a", 7, "review-context", fingerprint, draft),
      session.commentConfirmation.value,
    )
    ledger.confirm("/project-a/config.yaml")

    assertTrue(ledger.confirmed("/project-a/config.yaml"))
    assertFalse(ledger.confirmed("/project-b/config.yaml"))
    assertFalse(requestCommentConfirmation(session, "project", true, ledger, "/project-a/config.yaml", false, null, 0, "", null, emptyMap()))
    assertTrue(requestCommentConfirmation(session, "project", true, ledger, "/project-b/config.yaml", false, null, 0, "", null, emptyMap()))
    assertFalse(requestCommentConfirmation(session, "machine", true, ledger, "/project-b/config.yaml", false, null, 0, "", null, emptyMap()))
  }

  private fun machineBasis(path: String, fingerprint: String) =
    ConfigDraftBasis(
      path = path,
      fingerprint = ConfigFingerprint(fingerprint),
      candidatePaths = setOf("/home/user/.config/skill-bill/config.json", "/home/user/.skill-bill/config.json"),
      projectStorePath = null,
    )
}

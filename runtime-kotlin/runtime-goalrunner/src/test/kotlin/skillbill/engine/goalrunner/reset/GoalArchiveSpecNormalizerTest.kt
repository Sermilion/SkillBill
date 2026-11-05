package skillbill.engine.goalrunner.reset

import skillbill.application.storeAwareTestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.engine.goalrunner.archive.ParsedGoalArchiveBundle
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacketPayloadKeys
import skillbill.ports.goalrunner.model.GoalArchiveEnvelope
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GoalArchiveSpecNormalizerTest {
  @Test
  fun `legacy references in planning checkpoints payloads and execution identities map to archived specs`() {
    val bundle = legacyBundle()
    val destinationPort = FixedProjectStorePort(Files.createTempDirectory("goal-archive-normalizer-destination"))

    val normalized =
      requireNotNull(
        GoalArchiveSpecNormalizer(
          testDecompositionManifestValidator,
          storeAwareTestDecompositionManifestStore(destinationPort),
          "destination",
          "SKILL-245",
        ).normalize(bundle),
      )
    val expected = "specs/SKILL-245-goal/spec_subtask_1.md"
    val sharedPayload =
      JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(normalized.snapshot.sharedPreplans.single().preplanPayload))!!
    val planPayload =
      JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(normalized.snapshot.subtaskPlans.single().planPayload))!!
    val executionIdentity =
      normalized.snapshot.ownedTables.rows(GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE)!!.single()
        .orderedEntries().toMap()

    assertEquals(expected, sharedPayload[DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH])
    assertEquals("destination", sharedPayload[GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY])
    assertEquals(expected, planPayload[DecompositionPlanningPayloadKeys.SPEC_PATH])
    assertEquals(expected, normalized.snapshot.subtaskPlans.single().governedSubSpecPath)
    assertEquals(expected, normalized.snapshot.legacyPreparations.single().governedSubSpecPath)
    assertEquals(expected, executionIdentity[GoalArchiveSpecReferenceKeys.GOVERNED_SPEC_PATH])
    assertNotEquals("old-digest", sharedPayload[GoalArchiveSpecReferenceKeys.INTEGRITY_SHA256])
  }

  private fun legacyBundle(): ParsedGoalArchiveBundle {
    val sourceRoot = Files.createTempDirectory("goal-archive-normalizer-source")
    val legacySpec = sourceRoot.resolve(".feature-specs/SKILL-245-goal/spec_subtask_1.md").toString()
    val relativeLegacySpec = ".feature-specs/SKILL-245-goal/spec_subtask_1.md"
    val absolutePayload =
      JsonCodec.mapToJsonString(
        mapOf(
          DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH to legacySpec,
          GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY to "source",
          GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY to "SKILL-245",
          GoalArchiveSpecReferenceKeys.INTEGRITY_SHA256 to "old-digest",
        ),
      )
    val relativePayload =
      JsonCodec.mapToJsonString(mapOf(DecompositionPlanningPayloadKeys.SPEC_PATH to relativeLegacySpec))
    val snapshot =
      legacySnapshot(legacySpec, relativeLegacySpec, absolutePayload, relativePayload)
    return ParsedGoalArchiveBundle(
      fingerprint = "fingerprint",
      envelope = GoalArchiveEnvelope("1", "SKILL-245", "branch", "commit", "time"),
      specEntries = listOf("SKILL-245-goal/spec_subtask_1.md" to byteArrayOf(1)),
      snapshot = snapshot,
    )
  }

  private fun legacySnapshot(
    legacySpec: String,
    relativeLegacySpec: String,
    absolutePayload: String,
    relativePayload: String,
  ): GoalArchiveOwnedSnapshot {
    val identity = GoalPlanningIdentity("wf-parent", "SKILL-245", "source")
    val contractProvenance = GoalPlanningContractProvenance("parent", "manifest", "planning")
    return GoalArchiveOwnedSnapshot(
      workflows = emptyList(),
      sharedPreplans =
        listOf(
          SharedGoalPreplanCheckpoint(
            identity = identity,
            provenance = contractProvenance,
            payloadSha256 = "old-payload-digest",
            preplanPayload = absolutePayload,
          ),
        ),
      subtaskPlans =
        listOf(
          GoalSubtaskPlanCheckpoint(
            identity = identity,
            subtaskId = 1,
            manifestOrder = 0,
            governedSubSpecPath = relativeLegacySpec,
            subSpecHash = "sub-spec",
            provenance = contractProvenance,
            payloadSha256 = "old-plan-digest",
            planPayload = relativePayload,
          ),
        ),
      legacyPreparations =
        listOf(
          GoalPlanningPreparationRecord(
            parentGoalWorkflowId = "wf-parent",
            normalizedIssueKey = "SKILL-245",
            repositoryIdentity = "source",
            subtaskId = 1,
            governedSubSpecPath = legacySpec,
            preparationStatus = GoalPlanningPreparationState.PREPARED,
            provenance = GoalPlanningPreparationProvenance("parent", "subtask", "manifest"),
            preplanPayload = relativePayload,
            planPayload = absolutePayload,
          ),
        ),
      ownedTables =
        GoalArchiveOwnedTables.of(
          mapOf(
            GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE to
              listOf(
                GoalArchiveTableRow(
                  mapOf(GoalArchiveSpecReferenceKeys.GOVERNED_SPEC_PATH to legacySpec),
                ),
              ),
          ),
        ),
    )
  }
}

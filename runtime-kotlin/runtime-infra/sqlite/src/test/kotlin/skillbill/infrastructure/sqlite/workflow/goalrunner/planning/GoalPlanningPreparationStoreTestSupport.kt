package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import java.nio.file.Files
import java.nio.file.Path

internal fun tempDb(): Path =
  Files.createTempDirectory("runtime-kotlin-goal-planning-preparation").resolve("metrics.db")

internal fun identity() = GoalPlanningIdentity("goal-1", "SKILL-128", "repo-root-realpath-v1:/repository")

internal fun provenance() =
  GoalPlanningContractProvenance(
    parentSpecHash = "a".repeat(64),
    decompositionManifestHash = "b".repeat(64),
    planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
  )

internal fun sharedCheckpoint() =
  SharedGoalPreplanCheckpoint(
    identity = identity(),
    provenance = provenance(),
    payloadSha256 = "c".repeat(64),
    preplanPayload = "preplan-payload",
  )

internal fun descriptor(
  subtaskId: Int,
  order: Int,
) = GovernedGoalSubtaskDescriptor(
  subtaskId,
  order,
  ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
  "d".repeat(64),
)

internal fun planCheckpoint(
  subtaskId: Int,
  order: Int,
): GoalSubtaskPlanCheckpoint {
  val descriptor = descriptor(subtaskId, order)
  return GoalSubtaskPlanCheckpoint(
    identity = identity(),
    subtaskId = subtaskId,
    manifestOrder = order,
    governedSubSpecPath = descriptor.governedSubSpecPath,
    subSpecHash = descriptor.subSpecHash,
    provenance = provenance(),
    payloadSha256 = "e".repeat(64),
    planPayload = "plan-$subtaskId",
  )
}

package skillbill.engine.featuretask.slot.intake

import java.nio.file.Path

/** What the plan intake needs from whoever resolves and writes the parent spec; the goal runner implements it. */
interface PlanSpecIntakePreparation {
  fun resolvePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): PlanSpecSeed

  fun keepsOperatorSpec(specPath: Path): Boolean

  fun intakePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    resolution: PlanSpecSeed,
  ): PlanSpecSeed
}

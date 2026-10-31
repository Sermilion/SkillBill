package skillbill.engine.featuretask.slot.intake

import java.nio.file.Path

/** What the plan intake needs from whoever resolves and writes the parent spec; the goal runner implements it. */
public interface PlanSpecIntakePreparation {
  public fun resolvePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): PlanSpecSeed

  public fun keepsOperatorSpec(specPath: Path): Boolean

  public fun intakePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    resolution: PlanSpecSeed,
  ): PlanSpecSeed
}

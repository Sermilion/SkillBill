package skillbill.engine.featuretask.phase.planning

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeDecompositionPlanner(
  private val preparationRuntime: FeatureSpecPreparationRuntime,
  private val preparationWriter: FeatureSpecPreparationWriter,
  private val specPathResolver: FeatureSpecPathResolverPort,
) {
  fun existingParentSpec(
    repoRoot: Path,
    issueKey: String,
  ): Path? =
    when (val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))) {
      is FeatureSpecPathResolveResult.SingleMatch -> Path.of(resolved.specPath)
      is FeatureSpecPathResolveResult.Ambiguous -> resolved.matches.first()
      is FeatureSpecPathResolveResult.Explicit,
      is FeatureSpecPathResolveResult.NoMatch,
      -> null
    }

  fun verifyAuthoredBundle(
    repoRoot: Path,
    issueKey: String,
    runInvariants: FeatureTaskRuntimeRunInvariants,
  ): FeatureSpecWriteResult {
    val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))
    val parentSpecPath =
      (resolved as? FeatureSpecPathResolveResult.SingleMatch)?.specPath?.let(Path::of)
        ?: throw InvalidFeatureSpecPreparationRequestError(
          fieldPath = "parent_spec",
          reason = "plan must author exactly one .feature-specs/$issueKey-<slug>/spec.md bundle.",
        )
    preparationRuntime.prepareForFeatureSpec(
      FeatureSpecPreparationIntake(
        issueKey = issueKey,
        intendedOutcome = AUTHORED_BUNDLE_REASON,
        acceptanceCriteria = runInvariants.acceptanceCriteria,
        constraints = runInvariants.mandatesAndOverrides.ifEmpty { listOf("Runtime decompose planning stop.") },
        nonGoals = emptyList(),
      ),
    )
    return preparationWriter.verifyAuthored(repoRoot, parentSpecPath)
  }

  fun bundleTree(directory: Path): List<Path> = preparationWriter.listTree(directory)

  companion object {
    const val AUTHORED_BUNDLE_REASON = "Plan authored a governed spec bundle."
  }
}

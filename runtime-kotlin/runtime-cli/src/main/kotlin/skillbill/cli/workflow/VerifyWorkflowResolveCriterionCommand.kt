package skillbill.cli.workflow

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.engine.work.verify.VerifyCriterionResolutionService
import skillbill.engine.work.verify.VerifyCriterionResolveResult

private object CriterionResolutionKeys {
  const val WORKFLOW_ID = "workflow_id"
  const val CRITERION = "criterion"
  const val EVIDENCE = "evidence"
  const val RECORDED_AT = "recorded_at"
}

@Inject
class VerifyWorkflowResolveCriterionCommand(
  private val service: VerifyCriterionResolutionService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "resolve-criterion",
    "Record that an acceptance criterion a finished verify run graded PARTIAL or FAIL is now met.",
  ) {
  private val workflowId by argument(help = "Verify workflow id.")
  private val criterion by option("--criterion", help = "Criterion number from the run's completeness audit.")
    .int()
    .required()
  private val evidence by option("--evidence", help = "What now meets the criterion: commits, checks, results.")
    .required()
    .check("--evidence must not be blank.") { it.isNotBlank() }
  private val format by formatOption()

  override fun run() {
    when (val result = service.resolve(workflowId, criterion, evidence)) {
      is VerifyCriterionResolveResult.Recorded ->
        state.complete(
          mapOf(
            CriterionResolutionKeys.WORKFLOW_ID to workflowId,
            CriterionResolutionKeys.CRITERION to result.resolution.criterionNumber,
            CriterionResolutionKeys.EVIDENCE to result.resolution.evidence,
            CriterionResolutionKeys.RECORDED_AT to result.resolution.recordedAt.toString(),
          ),
          format,
        )
      VerifyCriterionResolveResult.WorkflowNotFound ->
        state.completeText("Verify workflow '$workflowId' was not found.", exitCode = 1)
      VerifyCriterionResolveResult.NotAudited ->
        state.completeText("Verify workflow '$workflowId' has no completeness audit to resolve.", exitCode = 1)
      is VerifyCriterionResolveResult.UnknownCriterion ->
        state.completeText(
          "Criterion $criterion was not graded. Graded criteria: ${result.graded.joinToString(", ")}.",
          exitCode = 1,
        )
      VerifyCriterionResolveResult.AlreadyMet ->
        state.completeText("Criterion $criterion already passed in this run.", exitCode = 1)
    }
  }
}

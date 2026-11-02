package skillbill.cli.review

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.service.ReviewFindingActionService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption

private object FindingActionKeys {
  const val ACTION_ID = "action_id"
  const val FIXED = "fixed"
  const val OPEN = "open"
  const val OTHER = "other"
}

@Inject
class StartReviewFindingActionCommand(private val service: ReviewFindingActionService, private val state: CliRunState) :
  DocumentedCliCommand("start-review-action", "Record the start of fixing or verifying a review's findings.") {
  private val runId by option("--run-id").required()
  private val kind by option("--action").choice(service.actionKinds).required()
  private val format by formatOption()

  override fun run() {
    val id = service.start(runId, kind)
    if (id == null) {
      state.completeText("No unresolved findings are available for this review.", exitCode = 1)
    } else {
      state.complete(mapOf(FindingActionKeys.ACTION_ID to id), format)
    }
  }
}

@Inject
class FinishReviewFindingActionCommand(
  private val service: ReviewFindingActionService,
  private val state: CliRunState,
) :
  DocumentedCliCommand("finish-review-action", "Record a findings action's result using its stored finding statuses.") {
  private val runId by option("--run-id").required()
  private val id by option("--action-id").long().required()
  private val failed by option("--failed").flag()
  private val format by formatOption()

  override fun run() {
    val action = service.finish(runId, id, completed = !failed)
    if (action == null) {
      state.completeText("The review action was not found.", exitCode = 1)
    } else {
      state.complete(
        mapOf(
          FindingActionKeys.ACTION_ID to action.id,
          FindingActionKeys.FIXED to action.fixed,
          FindingActionKeys.OPEN to action.open,
          FindingActionKeys.OTHER to action.other,
        ),
        format,
      )
    }
  }
}

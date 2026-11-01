package skillbill.cli.review

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.service.StandaloneReviewService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption

@Inject
class PrepareReviewCommand(
  private val service: StandaloneReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("prepare-review", "Register a finished standalone review's saved findings for feedback.") {
  private val invocationId by option("--invocation-id", help = "Exact standalone review invocation id.").required()
  private val format by formatOption()

  override fun run() {
    val prepared = service.prepareFindings(invocationId)
    if (prepared == null) {
      state.completeText(
        "No complete, settled review with matching finding identities is available for this invocation.",
        exitCode = 1,
      )
    } else {
      state.complete(prepared.toCliMap(), format)
    }
  }
}

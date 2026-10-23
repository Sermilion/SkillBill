package skillbill.engine.featuretask.qa.execution

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.qa.report.QaRetainedResource
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowIgnoredFilesCopyResult
import java.nio.file.Path

internal sealed interface QaCheckoutOpening {
  data class Opened(
    val checkout: Path,
    val warnings: List<String>,
  ) : QaCheckoutOpening

  data class Unavailable(val detail: String) : QaCheckoutOpening
}

@Inject
class QaCheckouts(
  private val gitOperations: WorkflowGitOperations,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun open(
    repoRoot: Path,
    workspace: QaWorkspace,
    revision: String,
    copyWorkingState: Boolean,
  ): QaCheckoutOpening {
    val checkout = workspace.directory.resolve(CHECKOUT_DIRECTORY)
    val added = gitOperations.addDetachedWorktree(repoRoot, checkout, revision)
    if (added !is WorkflowGitOperationResult.Ok) {
      return unavailable(repoRoot, checkout, "could not create the QA checkout at $revision: ${added.error}")
    }
    if (copyWorkingState) {
      val copied = gitOperations.copyWorkingState(repoRoot, checkout)
      if (copied !is WorkflowGitOperationResult.Ok) {
        return unavailable(repoRoot, checkout, "could not copy the uncommitted changes: ${copied.error}")
      }
    }
    return QaCheckoutOpening.Opened(checkout, ignoredFileWarnings(repoRoot, checkout))
  }

  internal fun close(
    repoRoot: Path,
    checkout: Path,
  ): List<QaRetainedResource> {
    val removed = gitOperations.removeWorktree(repoRoot, checkout)
    if (removed is WorkflowGitOperationResult.Ok) return emptyList()
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "qa checkout retained: seam=qa_checkout_cleanup value_expected=removed value_used=retained " +
        "worktree=$checkout reason=${removed.error.take(MAX_RECORD_REASON)}",
    )
    return listOf(QaRetainedResource(WORKTREE_KIND, checkout.toString()))
  }

  private fun ignoredFileWarnings(
    repoRoot: Path,
    checkout: Path,
  ): List<String> =
    when (val copied = gitOperations.copyIgnoredFiles(repoRoot, checkout, MAX_IGNORED_FILE_BYTES)) {
      is WorkflowIgnoredFilesCopyResult.Copied -> {
        if (copied.skippedPaths.isNotEmpty()) {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "qa checkout skipped ignored files: seam=qa_checkout value_expected=copied value_used=skipped " +
              "paths=${copied.skippedPaths.take(MAX_LISTED_PATHS).joinToString(",")}",
          )
        }
        emptyList()
      }
      is WorkflowIgnoredFilesCopyResult.Failed -> {
        val warning =
          "QA could not copy the repository's ignored files into its checkout, so a build that reads local " +
            "configuration may fail: ${copied.error.take(MAX_RECORD_REASON)}"
        RuntimeDiagnosticsBestEffortWarning.record(diagnostics, warning)
        listOf(warning)
      }
    }

  private fun unavailable(
    repoRoot: Path,
    checkout: Path,
    failure: String,
  ): QaCheckoutOpening.Unavailable {
    val retained = close(repoRoot, checkout).isNotEmpty()
    val cleanup =
      if (retained) " Run `git worktree remove --force $checkout` and `git worktree prune` to remove it." else ""
    val detail = "QA was skipped: $failure$cleanup"
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "qa skipped: seam=qa_checkout value_expected=checkout value_used=isolation_unavailable " +
        "reason=${detail.take(MAX_RECORD_REASON)}",
    )
    return QaCheckoutOpening.Unavailable(detail)
  }

  private companion object {
    const val CHECKOUT_DIRECTORY = "checkout"
    const val WORKTREE_KIND = "worktree"
    const val MAX_IGNORED_FILE_BYTES = 1_048_576L
    const val MAX_LISTED_PATHS = 20
    const val MAX_RECORD_REASON = 200
  }
}

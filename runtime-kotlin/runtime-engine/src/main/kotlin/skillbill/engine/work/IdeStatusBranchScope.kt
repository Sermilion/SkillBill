package skillbill.engine.work

import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.idestatus.model.IdeStatusWorkflowFamily

internal object IdeStatusBranchScope {
  fun matchesCandidate(
    candidate: IdeStatusCandidate,
    branch: String,
  ): Boolean =
    when {
      candidate.workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL -> true
      candidate.standaloneStatus?.monitorTarget != null -> true
      candidate.standaloneStatus != null ->
        candidate.branchCorrelation == null ||
          candidate.branchCorrelation == "HEAD" ||
          candidate.branchCorrelation == branch
      candidate.workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        (
          candidate.branchCorrelation == null ||
            candidate.branchCorrelation == "HEAD" ||
            candidate.branchCorrelation == branch
        ) &&
          (
            candidate.branchCorrelation == branch ||
              candidate.issueKey?.let { branchReferencesIssueKey(branch, it) } == true
          )
      else -> candidate.issueKey?.let { branchReferencesIssueKey(branch, it) } == true
    }

  fun branchReferencesIssueKey(
    branch: String,
    issueKey: String,
  ): Boolean {
    val haystack = branch.lowercase()
    val needle = issueKey.trim().lowercase()
    if (needle.isEmpty()) return false
    var index = haystack.indexOf(needle)
    while (index >= 0) {
      val before = haystack.getOrNull(index - 1)
      val after = haystack.getOrNull(index + needle.length)
      if (before?.isLetterOrDigit() != true && after?.isLetterOrDigit() != true) return true
      index = haystack.indexOf(needle, index + 1)
    }
    return false
  }
}

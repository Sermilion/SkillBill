package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.lifecycle.subtask.SubtaskCommitPreservationRequest
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

/** The git checkpoint writes one run makes: the subtask commit and the checkpoint refs that preserve its history. */
internal interface PhaseRunCheckpoints {
  /**
   * Creates or amends the subtask commit [request] decides, preserving the pre-amend commit under its checkpoint ref.
   * Returns the commit sha, or the failure that left HEAD unchanged.
   */
  fun commitSubtask(request: SubtaskCommitPreservationRequest): WorkflowGitOperationResult
}

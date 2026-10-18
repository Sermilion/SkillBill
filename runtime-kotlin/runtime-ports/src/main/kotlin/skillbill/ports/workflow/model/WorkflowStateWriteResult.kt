package skillbill.ports.workflow.model

sealed interface WorkflowStateWriteResult {
  data object Accepted : WorkflowStateWriteResult

  data object TerminalRefused : WorkflowStateWriteResult
}

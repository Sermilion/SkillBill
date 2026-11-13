package skillbill.ports.taskruntime.assignment

interface WorkflowAgentAssignmentsValidator {
  fun canonicalize(
    encoded: ByteArray,
    sourceLabel: String,
  ): ByteArray
}

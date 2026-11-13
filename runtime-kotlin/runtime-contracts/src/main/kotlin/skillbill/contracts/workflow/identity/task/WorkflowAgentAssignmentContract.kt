package skillbill.contracts.workflow.identity.task

const val WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION: String = "0.1"

object WorkflowAgentAssignmentKeys {
  const val CONTRACT_VERSION: String = "contract_version"
  const val ADMISSION_WORKFLOW_ID: String = "admission_workflow_id"
  const val POLICY_MODE: String = "policy_mode"
  const val INVOKED_AGENT_ID: String = "invoked_agent_id"
  const val CONFIGURED_AGENT_OVERRIDE_ID: String = "configured_agent_override_id"
  const val PHASE_ASSIGNMENTS: String = "phase_assignments"
  const val AGENT_ID: String = "agent_id"
  const val TIER: String = "tier"
  const val SOURCE: String = "source"
}

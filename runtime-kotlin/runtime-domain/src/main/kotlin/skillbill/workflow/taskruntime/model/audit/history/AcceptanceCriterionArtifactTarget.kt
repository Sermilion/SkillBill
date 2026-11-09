package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily

enum class AcceptanceCriterionMigrationTarget { COMPATIBLE, VERIFIED }

data class AcceptanceCriterionArtifactTarget(val family: DurableWorkflowArtifactFamily, val version: String)

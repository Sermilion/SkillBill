package skillbill.engine.featuretask.lifecycle.execution

import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.infrastructure.contracts.workflow.WorkflowAgentAssignmentsSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics

fun workflowAgentAssignmentStoreForTests(database: DatabaseSessionFactory): WorkflowAgentAssignmentStore =
  WorkflowAgentAssignmentStore(
    database,
    WorkflowAgentAssignmentsSchemaValidator(),
    FeatureTaskRuntimeExecutionPlanSchemaValidator(),
    NoopRuntimeDiagnostics,
  )

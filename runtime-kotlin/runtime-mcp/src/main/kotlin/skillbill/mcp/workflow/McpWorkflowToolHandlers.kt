package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.contracts.workflow.payload.WorkflowArtifactKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.FeatureTaskRouteScope

private val VERIFY: WorkflowFamilyKind = WorkflowFamilyKind.VERIFY

internal fun workflowOpen(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.workflowService.open(
    WorkflowServiceOpenArgs(
      kind = VERIFY,
      sessionId = arguments.string(WorkflowWirePayloadKeys.SESSION_ID),
      currentStepId = arguments.optionalString(WorkflowWirePayloadKeys.CURRENT_STEP_ID),
      issueKey = arguments.optionalString(SharedPayloadKeys.ISSUE_KEY),
      repositoryIdentity = arguments.optionalString(McpToolPayloadKeys.REPOSITORY_IDENTITY),
      governedSpecPath = arguments.optionalString(McpToolPayloadKeys.GOVERNED_SPEC_PATH),
      routeScope = FeatureTaskRouteScope.STANDALONE,
    ),
  ).toMcpMap()

internal fun workflowUpdate(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.workflowService.update(
    VERIFY,
    arguments.optionalMap(McpToolPayloadKeys.ARTIFACTS_PATCH).let { artifactsPatch ->
      WorkflowUpdateRequest(
        workflowId = arguments.string(SharedPayloadKeys.WORKFLOW_ID),
        workflowStatus = arguments.string(WorkflowWirePayloadKeys.WORKFLOW_STATUS),
        currentStepId = arguments.string(WorkflowWirePayloadKeys.CURRENT_STEP_ID),
        stepUpdates = arguments.optionalListMap(McpToolPayloadKeys.STEP_UPDATES)?.let(WorkflowStepUpdates::from),
        artifactsPatch = artifactsPatch?.let(WorkflowArtifactPatch::from),
        planningResult =
          artifactsPatch?.get(WorkflowArtifactKeys.PLAN)
            ?.let(JsonCodec::anyToStringAnyMap)
            ?.let { DecompositionPlanningResult.fromWireMap(it, "mcp.artifacts_patch.plan") },
        sessionId = arguments.string(WorkflowWirePayloadKeys.SESSION_ID),
      )
    },
  ).toMcpMap()

internal fun workflowGet(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> = component.workflowService.get(VERIFY, arguments.string(SharedPayloadKeys.WORKFLOW_ID)).toMcpMap()

internal fun workflowList(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.workflowService.list(VERIFY, arguments.int(WorkflowWirePayloadKeys.LIMIT, default = 20)).toMcpMap()

internal fun workflowLatest(component: McpComponent): Map<String, Any?> =
  component.workflowService.latest(VERIFY).toMcpMap()

internal fun workflowResume(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.workflowService.resume(VERIFY, arguments.string(SharedPayloadKeys.WORKFLOW_ID)).toMcpMap()

internal fun workflowContinue(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.workflowService.continueWorkflow(
    VERIFY,
    arguments.string(SharedPayloadKeys.WORKFLOW_ID),
    subtaskId = null,
  ).toMcpMap()

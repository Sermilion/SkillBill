package skillbill.cli.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.service.TelemetryService
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.service.WorkflowService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.drainTelemetryOnCompletion
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunner
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class FeatureTaskRuntimeRunExecution(
  private val runner: FeatureTaskRuntimeRunner,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val workerCoordinator: FeatureTaskRuntimeWorkerCoordinator,
  private val runInvariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val workflowService: WorkflowService,
  private val telemetryService: TelemetryService,
  private val diagnostics: RuntimeDiagnostics,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) {
  internal fun run(
    options: FeatureTaskRuntimePhaseAgentCommand,
    prepared: PreparedRuntimeRun,
  ) = execute(options, prepared, resolveWorkflowId(options, prepared))

  internal fun execute(
    options: FeatureTaskRuntimePhaseAgentCommand,
    prepared: PreparedRuntimeRun,
    workflowId: String,
  ) {
    val issueKey = prepared.issueKey
    val specPath = prepared.specPath
    val report =
      workerCoordinator.runOwned(
        workflowId,
        executionPlans.resolveInputs(
          prepared.repoRoot,
          prepared.goalContinuation?.qualityGateSelection,
          prepared.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
          options.maxWallClockMinutes.takeIf { it > 0 }?.minutes,
          workflowId,
        ),
        FeatureTaskExecutionIdentity(
          workflowId = workflowId,
          normalizedIssueKey = issueKey.trim().uppercase(),
          repositoryIdentity = inputs.repositoryEnclosingRootPort.repositoryIdentity(prepared.repoRoot),
          governedSpecPath =
            inputs.repositoryEnclosingRootPort.governedSpecPathForCli(
              prepared.repoRoot,
              Path.of(specPath),
            ),
          mode = FeatureTaskWorkflowMode.RUNTIME,
          routeScope =
            if (prepared.goalContinuation != null) {
              FeatureTaskRouteScope.GOAL_CHILD
            } else {
              FeatureTaskRouteScope.STANDALONE
            },
        ),
        requestedReviewSelection =
          (prepared.goalContinuation?.codeReviewMode ?: options.requestedCodeReviewMode())
            ?.let { RuntimeReviewSelection.valueOf(it.name) },
      ) { admittedExecution ->
        val sourceInvariants = runInvariantsSource.read(Path.of(specPath))
        val request =
          FeatureTaskRuntimeRunRequest(
            issueKey = issueKey,
            workflowId = workflowId,
            admittedExecution = admittedExecution,
            sessionId =
              "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$workflowId",
            runInvariants =
              sourceInvariants.copy(
                codeReviewMode =
                  admittedExecution.reviewMode
                    ?: sourceInvariants.codeReviewMode,
                agentAddonSelection = prepared.agentAddonSelection.persisted,
              ),
            invokedAgentId = prepared.invokedAgentId,
            agentAssignment = prepared.agentAssignment,
            modelAssignment = prepared.modelAssignment,
            compactionSettings = prepared.compactionSettings,
            environment = inputs.environment,
            repoRoot = prepared.repoRoot,
            timeout = options.maxWallClockMinutes.takeIf { it > 0 }?.minutes,
            requestedCodeReviewMode = options.requestedCodeReviewMode(),
            goalContinuation = prepared.goalContinuation,
            operatorDecision = prepared.operatorDecision,
            agentAddonSelection = prepared.agentAddonSelection,
            eventSink = runtimeRunEventSink(inputs, options.monitor),
          )
        inputs.featureTaskRuntimeRunOverride?.invoke(request) ?: runner.run(request)
      }
    val payload = report.toRuntimeRunCliMap()
    state.completeText(runtimeRunText(report), payload, exitCode = report.runtimeRunExitCode())
    drainTelemetryOnCompletion(telemetryService, diagnostics)
  }

  private fun resolveWorkflowId(
    options: FeatureTaskRuntimePhaseAgentCommand,
    prepared: PreparedRuntimeRun,
  ): String =
    options.explicitWorkflowId?.takeIf(String::isNotBlank)
      ?: workflowService.openRuntimeWorkflowId(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          sessionId = "",
          currentStepId = null,
          issueKey = prepared.issueKey,
          repositoryIdentity = inputs.repositoryEnclosingRootPort.repositoryIdentity(prepared.repoRoot),
          governedSpecPath =
            inputs.repositoryEnclosingRootPort.governedSpecPathForCli(
              prepared.repoRoot,
              Path.of(prepared.specPath),
            ),
          routeScope =
            if (options.goalParentIssueKey != null) {
              FeatureTaskRouteScope.GOAL_CHILD
            } else {
              FeatureTaskRouteScope.STANDALONE
            },
          executionPlan =
            executionPlans.resolveCreation(
              FeatureTaskRuntimeExecutionPlanCreationRequest(
                repoRoot = prepared.repoRoot,
                definition = SkeletonDefinition.forRun(prepared.goalContinuation != null),
                reviewMode =
                  prepared.goalContinuation?.codeReviewMode ?: options.requestedCodeReviewMode()
                    ?: runInvariantsSource.read(Path.of(prepared.specPath)).codeReviewMode,
                qualityGate = prepared.goalContinuation?.qualityGateSelection,
                validationDepth = prepared.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
                timeout = options.maxWallClockMinutes.takeIf { it > 0 }?.minutes,
              ),
            ),
        ),
      )
}

package skillbill.workflow.taskruntime.handoff

import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeHandoffContract {
  fun selectLatestOutputsByPhase(
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  ): Map<String, FeatureTaskRuntimePhaseOutput> {
    val latest = LinkedHashMap<String, FeatureTaskRuntimePhaseOutput>()
    recordedOutputs.forEach { output ->
      val existing = latest[output.phaseId]
      if (existing == null || output.iteration >= existing.iteration) {
        latest[output.phaseId] = output
      }
    }
    return latest
  }

  fun resolveUpstreamOutputs(
    declaration: FeatureTaskRuntimePhaseDeclaration,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  ): FeatureTaskRuntimeResolvedUpstreamOutputs {
    val latestByPhase = selectLatestOutputsByPhase(recordedOutputs)
    val resolved = LinkedHashMap<String, FeatureTaskRuntimePhaseOutput>()

    declaration.projectionDeclarations.forEach { projection ->
      val sourceRef = projection.sourceRef
      if (sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput) {
        latestByPhase[sourceRef.producingPhaseId]?.let { resolved[sourceRef.producingPhaseId] = it }
      }
    }
    FeatureTaskRuntimePhaseWorkflowDefinition.runtimeProjectorProducerPhaseIds(declaration.phaseId).forEach { phaseId ->
      latestByPhase[phaseId]?.let { resolved[phaseId] = it }
    }
    return FeatureTaskRuntimeResolvedUpstreamOutputs(resolved)
  }

  fun assembleHandoff(request: FeatureTaskRuntimeHandoffAssemblyRequest): FeatureTaskRuntimePhaseHandoff =
    FeatureTaskRuntimePhaseHandoff(
      phaseId = request.declaration.phaseId,
      runInvariants = request.runInvariants,
      upstreamOutputs = resolveUpstreamOutputs(request.declaration, request.recordedOutputs),
      derivedContextKeys = request.declaration.derivedContextKeys,
      projectionDeclarations = request.declaration.projectionDeclarations,
      repositoryCheckpoint = request.repositoryCheckpoint,
      expectedRepositoryCheckpoint = request.expectedRepositoryCheckpoint,
      branchIdentity = request.branchIdentity,
      baseBranch = request.baseBranch,
      validationDepth = request.validationDepth,
      unselectedStepIds = request.unselectedStepIds,
      drivingVerdict = request.drivingVerdict,
      repairLedger = request.repairLedger,
    )
}

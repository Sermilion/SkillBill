package skillbill.engine.featuretask.prepare

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeSpecGate(
  private val specScratchStore: SpecScratchStore,
  private val specStatusWriter: FeatureTaskRuntimeSpecStatusWriter,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun finalizeSingleSpecOnTerminal(
    request: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    specSource: SpecSource,
    finalizingAgentId: (FeatureTaskRuntimeRunRequest) -> String?,
  ) {
    reconcileSingleSpecAgentLine(request, report) { finalizingAgentId(request) }
    deleteSingleSpecScratchOnTerminalSuccess(request, report, specSource)
  }

  private fun reconcileSingleSpecAgentLine(
    request: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    finalizingAgentId: () -> String?,
  ) {
    if (request.goalContinuation != null || report !is FeatureTaskRuntimeRunReport.Completed) {
      return
    }
    val agentId = finalizingAgentId()?.takeIf(String::isNotBlank) ?: return
    runCatching {
      specStatusWriter.writeFinalizingAgent(Path.of(request.runInvariants.specReference), agentId)
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Feature-task-runtime single-spec Agent line reconciliation for workflow " +
          "'${request.workflowId}' failed; the successful run is unaffected.",
        error,
      )
    }
  }

  private fun deleteSingleSpecScratchOnTerminalSuccess(
    request: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    specSource: SpecSource,
  ) {
    if (specSource != SpecSource.LINEAR ||
      request.goalContinuation != null ||
      report !is FeatureTaskRuntimeRunReport.Completed
    ) {
      return
    }
    val specDir =
      resolvedParentSpecPath(request.repoRoot, Path.of(request.runInvariants.specReference)).parent
        ?: return
    runCatching { specScratchStore.deleteDirectoryIfExists(specDir) }
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Feature-task-runtime linear-mode spec scratch deletion at '$specDir' failed; " +
            "the successful run is unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }
}

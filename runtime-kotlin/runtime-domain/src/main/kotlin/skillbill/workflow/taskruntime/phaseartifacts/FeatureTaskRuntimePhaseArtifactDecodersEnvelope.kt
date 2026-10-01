package skillbill.workflow.taskruntime.phaseartifacts

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry

internal fun decomposeTerminalFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeDecomposeTerminal? {
  if (FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY !in artifacts) return null
  val raw = artifacts[FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY]
  val entryMap =
    JsonCodec.anyToStringAnyMap(raw)
      ?: schemaError(
        "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY' must decode to a map.",
      )
  return FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap(entryMap)
}

internal fun phaseLedgerFrom(artifacts: Map<String, Any?>): List<FeatureTaskRuntimePhaseLedgerEntry> {
  if (FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY !in artifacts) return emptyList()
  val raw = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY]
  val rawList =
    raw as? List<*>
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY' must decode to a list.",
      )
  return rawList.map { item ->
    val entryMap =
      JsonCodec.anyToStringAnyMap(item)
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime phase ledger entry must decode to a string-keyed map.",
        )
    FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entryMap)
  }
}

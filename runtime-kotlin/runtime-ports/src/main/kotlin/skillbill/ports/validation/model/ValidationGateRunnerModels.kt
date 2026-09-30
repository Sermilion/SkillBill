package skillbill.ports.validation.model

import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Path

data class ValidationGateFinding(
  val module: String,
  val ruleOrTestId: String,
  val message: String,
  val location: String?,
) {
  fun identity(): String = "$module|$ruleOrTestId|$message|${location.orEmpty()}"
}

enum class ValidationGateFindingParseMode {
  ARTIFACTS_ONLY,
  COLLECT_ALL,
}

data class ValidationGateRunRequest(
  val repoRoot: Path,
  val argv: List<String>,
  val cacheMode: ValidationGateCacheMode,
  val declaration: ValidationGateDeclaration,
  val terminalVerifying: Boolean,
  val findingParseMode: ValidationGateFindingParseMode = ValidationGateFindingParseMode.ARTIFACTS_ONLY,
)

data class ValidationGateRunResult(
  val exitCode: Int,
  val durationMs: Long,
  val outcome: ValidationGateRunOutcome,
  val cacheMode: ValidationGateCacheMode,
  val executedWorkUnits: Int,
  val executedCheckIdentities: List<String>,
  val findings: List<ValidationGateFinding>,
  val stdout: String = "",
  val command: String = "",
)

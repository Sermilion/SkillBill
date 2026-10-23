package skillbill.engine.diagnostics.block.report

import me.tatarka.inject.annotations.Inject
import skillbill.model.RuntimeVersion
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.system.RuntimeBuildIdentityPort
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportRuntimeFacts

@Inject
class ProductReportRuntimeFactsCollector(
  private val runtimeVersion: RuntimeVersion,
  private val host: HostPlatformPort,
  private val buildIdentity: RuntimeBuildIdentityPort,
) {
  fun collect(diagnosis: BlockIncidentDiagnosisRecord): ProductReportRuntimeFacts =
    ProductReportRuntimeFacts(
      runtimeVersion = runtimeVersion.value.takeIf(String::isNotBlank),
      runtimeBuild = buildIdentity.readIdentity(),
      osFamily = host.osName.osFamily(),
      providerId = diagnosis.providerId,
      phaseStrategyId = diagnosis.phaseStrategyId,
      modelId = diagnosis.modelId,
    )

  private fun String.osFamily(): String? =
    when {
      contains("windows", ignoreCase = true) -> "Windows"
      contains("mac", ignoreCase = true) || contains("darwin", ignoreCase = true) -> "macOS"
      contains("linux", ignoreCase = true) -> "Linux"
      isNotBlank() -> "Other"
      else -> null
    }
}

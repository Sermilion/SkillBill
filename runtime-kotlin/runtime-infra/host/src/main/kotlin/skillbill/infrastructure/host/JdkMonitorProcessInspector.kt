package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection

@Inject
class JdkMonitorProcessInspector(
  private val diagnostics: RuntimeDiagnostics,
  private val platform: HostPlatformPort,
) : MonitorProcessInspector {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity = currentProcessIdentity(platform, diagnostics)

  override fun inspect(recorded: FeatureTaskRuntimeProcessIdentity): FeatureTaskRuntimeProcessInspection =
    runCatching { currentProcess() }.fold(
      onSuccess = { local -> inspectRecordedProcess(recorded, local, platform, diagnostics) },
      onFailure = {
        FeatureTaskRuntimeProcessInspection.Unsupported(it.message ?: "Local process identity is unavailable.")
      },
    )
}

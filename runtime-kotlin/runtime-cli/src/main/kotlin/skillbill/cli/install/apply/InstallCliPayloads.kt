package skillbill.cli.install.apply

import skillbill.application.install.InstallService
import skillbill.install.model.InstallPlan
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.toInstallPlanContract

internal fun installPlanPayload(
  plan: InstallPlan,
  installService: InstallService,
): Map<String, Any?> {
  val wireMap = plan.toInstallPlanContract().toPayload()

  installService.validateInstallPlanWire(plan)
  return wireMap
}

internal fun windowsPreflightPayload(preflight: WindowsSymlinkPreflight): Map<String, Any?> =
  mapOf(
    "state" to preflight.state.name.lowercase(),
    "decision" to preflight.decision.name.lowercase(),
    "message" to preflight.message,
  )

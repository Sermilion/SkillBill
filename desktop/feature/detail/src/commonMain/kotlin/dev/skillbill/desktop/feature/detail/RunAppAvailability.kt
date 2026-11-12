package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import dev.skillbill.desktop.core.domain.qa.QaRunTarget
import dev.skillbill.desktop.core.domain.qa.resolveQaRunTarget

internal const val RUN_APP_REFRESH_REASON = "Refresh the detail before using run controls."
internal const val RUN_APP_REPAIR_REASON = "Off while a CI repair edits the checkout. Run app turns back on when monitoring resumes."
internal const val RUN_APP_LIFECYCLE_REASON = "Run app turns on when the goal completes or reaches monitoring."
internal const val RUN_APP_ADB_MISSING_REASON = "adb not found. Install Android SDK Platform-Tools, or set ANDROID_HOME."
internal const val RUN_APP_ADB_FAILED_REASON = "adb could not list devices. Open the device panel for details."
internal const val RUN_APP_LOOKING_REASON = "Looking for devices…"
internal const val RUN_APP_NO_DEVICE_REASON = "No device. Plug in a phone with USB debugging on, or create an AVD."

internal data class RunAppTarget(
  val pick: QaDevicePick,
  val boots: Boolean,
  val auto: Boolean,
  val sourcePick: QaDevicePick?,
  val monospace: Boolean,
)

internal sealed interface RunAppAvailability {
  data object Hidden : RunAppAvailability
  data class Disabled(val reason: String, val target: RunAppTarget?) : RunAppAvailability
  data class Enabled(val target: RunAppTarget) : RunAppAvailability
}

internal fun runAppAvailability(
  item: WorkItem,
  discovery: QaDeviceDiscovery?,
  pick: QaDevicePick?,
  refreshFailed: Boolean,
): RunAppAvailability {
  val target = resolveQaRunTarget(discovery, pick)?.toRunAppTarget()
  val platforms = target?.pick?.kind?.let(::setOf) ?: pick?.kind?.let(::setOf) ?: QaDevicePickKind.entries.toSet()
  if (item.kind != WorkKind.Goal || item.appRunPlatforms.intersect(platforms.map { it.platform }.toSet()).isEmpty()) {
    return RunAppAvailability.Hidden
  }
  fun disabled(reason: String) = RunAppAvailability.Disabled(reason, target)
  if (refreshFailed) return disabled(RUN_APP_REFRESH_REASON)
  if (item.control.phaseId == "monitor_fix") return disabled(RUN_APP_REPAIR_REASON)
  if (item.lifecycle != Lifecycle.Terminal && !(item.lifecycle == Lifecycle.Active && item.control.phaseId == "monitor")) {
    return disabled(RUN_APP_LIFECYCLE_REASON)
  }
  when (discovery) {
    QaDeviceDiscovery.AdbMissing -> return disabled(RUN_APP_ADB_MISSING_REASON)
    is QaDeviceDiscovery.AdbFailed -> return disabled(RUN_APP_ADB_FAILED_REASON)
    null -> return disabled(RUN_APP_LOOKING_REASON)
    is QaDeviceDiscovery.Listed -> Unit
  }
  if (target == null) return disabled(RUN_APP_NO_DEVICE_REASON)
  return RunAppAvailability.Enabled(target)
}

internal fun QaRunTarget.toRunAppTarget() = RunAppTarget(pick, boots, auto, sourcePick, monospace)

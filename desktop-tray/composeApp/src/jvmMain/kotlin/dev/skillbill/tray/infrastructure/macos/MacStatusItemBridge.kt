package dev.skillbill.tray.infrastructure.macos

import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import dev.skillbill.tray.presentation.TrayStateKind
import java.nio.file.Files

object MacStatusItemBridge {
  @Volatile private var primaryAction: (() -> Unit)? = null
  @Volatile private var menuAction: ((Int) -> Unit)? = null
  @Volatile private var failureAction: (() -> Unit)? = null
  @Volatile private var dismissAction: (() -> Unit)? = null
  @Volatile private var loaded = false

  fun install(onPrimary: () -> Unit, onMenuAction: (Int) -> Unit, onFailure: () -> Unit, onDismiss: () -> Unit) {
    primaryAction = onPrimary
    menuAction = onMenuAction
    failureAction = onFailure
    dismissAction = onDismiss
    try {
      val library = MacStatusItemBridge::class.java.getResourceAsStream("/macos/libskillbilltray.dylib")
        ?: error("native tray library unavailable")
      val path = Files.createTempFile("skillbill-tray-", ".dylib")
      library.use { Files.copy(it, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
      path.toFile().deleteOnExit()
      System.load(path.toAbsolutePath().toString())
      nativeInstall()
      loaded = true
    } catch (_: Exception) {
      RuntimeDiagnostics.record("mac_tray_registration_failed")
      onFailure()
    } catch (_: LinkageError) {
      RuntimeDiagnostics.record("mac_tray_registration_failed")
      onFailure()
    }
  }

  fun update(kind: TrayStateKind, count: Int, goals: List<String>, phases: List<String>) {
    if (loaded) nativeUpdate(kind.name.lowercase(), count, goals.toTypedArray(), phases.toTypedArray())
  }

  fun dispose() {
    if (loaded) nativeDispose()
    loaded = false
    primaryAction = null
    menuAction = null
    failureAction = null
    dismissAction = null
  }

  data class StatusItemAnchor(
    val iconX: Double,
    val iconY: Double,
    val iconWidth: Double,
    val iconHeight: Double,
    val screenX: Double,
    val screenY: Double,
    val screenWidth: Double,
    val screenHeight: Double,
    val backingScale: Double,
  )

  @Volatile var statusItemAnchor: StatusItemAnchor? = null
    private set

  @JvmStatic fun onPrimaryAction(
    iconX: Double, iconY: Double, iconWidth: Double, iconHeight: Double,
    screenX: Double, screenY: Double, screenWidth: Double, screenHeight: Double,
    backingScale: Double,
  ) {
    statusItemAnchor = StatusItemAnchor(iconX, iconY, iconWidth, iconHeight, screenX, screenY, screenWidth, screenHeight, backingScale)
    primaryAction?.invoke()
  }
  @JvmStatic fun onMenuAction(index: Int) { menuAction?.invoke(index) }
  @JvmStatic fun onTrayRegistrationFailed() { failureAction?.invoke() }
  @JvmStatic fun onDismissPopover() { dismissAction?.invoke() }

  @JvmStatic private external fun nativeInstall()
  @JvmStatic private external fun nativeUpdate(kind: String, count: Int, goals: Array<String>, phases: Array<String>)
  @JvmStatic private external fun nativeDispose()
}

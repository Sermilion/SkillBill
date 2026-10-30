package dev.skillbill.desktop.app.tray.macos

import dev.skillbill.desktop.core.domain.model.TrayStatus
import java.nio.file.Files

object MacStatusItemBridge {
  @Volatile private var primaryAction: (() -> Unit)? = null
  @Volatile private var menuAction: ((Int) -> Unit)? = null
  @Volatile private var dismissAction: (() -> Unit)? = null
  @Volatile private var loaded = false

  fun install(onPrimary: () -> Unit, onMenuAction: (Int) -> Unit, onAvailable: () -> Unit, onFailure: () -> Unit, onDismiss: () -> Unit) {
    primaryAction = onPrimary
    menuAction = onMenuAction
    dismissAction = onDismiss
    try {
      val library = MacStatusItemBridge::class.java.getResourceAsStream("/macos/libskillbilltray.dylib")
        ?: error("native tray library unavailable")
      val path = Files.createTempFile("skillbill-tray-", ".dylib")
      library.use { Files.copy(it, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
      path.toFile().deleteOnExit()
      System.load(path.toAbsolutePath().toString())
      loaded = nativeInstall()
      if (loaded) onAvailable() else { nativeDispose(); onFailure() }
    } catch (_: Exception) {
      onFailure()
    } catch (_: LinkageError) {
      onFailure()
    }
  }

  fun update(kind: TrayStatus, count: Int, goals: List<String>, phases: List<String>) {
    val token = when (kind) {
      TrayStatus.Failed -> "failed"
      TrayStatus.NeedsYou -> "needs_you"
      TrayStatus.Running -> "running"
      TrayStatus.Idle -> "idle"
    }
    if (loaded) nativeUpdate(token, count, goals.toTypedArray(), phases.toTypedArray())
  }

  fun dispose() {
    if (loaded) nativeDispose()
    loaded = false
    primaryAction = null
    menuAction = null
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
  @JvmStatic fun onDismissPopover() { dismissAction?.invoke() }

  @JvmStatic private external fun nativeInstall(): Boolean
  @JvmStatic private external fun nativeUpdate(kind: String, count: Int, goals: Array<String>, phases: Array<String>)
  @JvmStatic private external fun nativeDispose()
}

package dev.skillbill.desktop.app.tray.macos

import dev.skillbill.desktop.app.tray.TrayDiagnostics
import dev.skillbill.desktop.core.data.runtime.currentDesktopTempRoot
import dev.skillbill.desktop.core.domain.model.TrayStatus
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface MacStatusItemOperations {
  fun install(imagePng: ByteArray): Boolean
  fun update(imagePng: ByteArray, kind: String, count: Int, goals: Array<String>, phases: Array<String>): Boolean
  fun dispose()
}

object MacStatusItemBridge {
  @Volatile private var primaryAction: (() -> Unit)? = null
  @Volatile private var menuAction: ((Int) -> Unit)? = null
  @Volatile private var dismissAction: (() -> Unit)? = null
  @Volatile private var onFailure: (() -> Unit)? = null
  @Volatile private var loaded = false

  internal var operations: MacStatusItemOperations = ProductionOperations

  fun install(
    imagePng: ByteArray,
    onPrimary: () -> Unit,
    onMenuAction: (Int) -> Unit,
    onAvailable: () -> Unit,
    onFailure: () -> Unit,
    onDismiss: () -> Unit,
  ) {
    if (loaded) dispose()
    clearCallbacks()
    statusItemAnchor = null
    primaryAction = onPrimary
    menuAction = onMenuAction
    dismissAction = onDismiss
    this.onFailure = onFailure
    loaded = try {
      operations.install(imagePng)
    } catch (_: Exception) {
      false
    } catch (_: LinkageError) {
      false
    }
    if (loaded) onAvailable() else fail()
  }

  fun update(imagePng: ByteArray, kind: TrayStatus, count: Int, goals: List<String>, phases: List<String>): Boolean {
    val token = when (kind) {
      TrayStatus.Failed -> "failed"
      TrayStatus.NeedsYou -> "needs_you"
      TrayStatus.Running -> "running"
      TrayStatus.Idle -> "idle"
    }
    if (!loaded) return false
    val updated = try {
      operations.update(imagePng, token, count, goals.toTypedArray(), phases.toTypedArray())
    } catch (_: Exception) {
      false
    } catch (_: LinkageError) {
      false
    }
    if (!updated) fail()
    return updated
  }

  fun dispose() {
    try {
      operations.dispose()
    } finally {
      loaded = false
      clearCallbacks()
      statusItemAnchor = null
    }
  }

  private fun fail() {
    val failure = onFailure
    loaded = false
    statusItemAnchor = null
    try {
      operations.dispose()
    } catch (_: Exception) {
    } catch (_: LinkageError) {
    }
    clearCallbacks()
    TrayDiagnostics.record("tray_icon_failed")
    failure?.invoke()
  }

  private fun clearCallbacks() {
    primaryAction = null
    menuAction = null
    dismissAction = null
    onFailure = null
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

  private object ProductionOperations : MacStatusItemOperations {
    private var libraryLoaded = false

    override fun install(imagePng: ByteArray): Boolean {
      if (!libraryLoaded) {
        val library = MacStatusItemBridge::class.java.getResourceAsStream("/macos/libskillbilltray.dylib")
          ?: error("native tray library unavailable")
        val path = Files.createTempFile(currentDesktopTempRoot(), "skillbill-tray-", ".dylib")
        library.use { Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING) }
        path.toFile().deleteOnExit()
        System.load(path.toAbsolutePath().toString())
        libraryLoaded = true
      }
      return nativeInstall(imagePng)
    }

    override fun update(imagePng: ByteArray, kind: String, count: Int, goals: Array<String>, phases: Array<String>) =
      nativeUpdate(imagePng, kind, count, goals, phases)

    override fun dispose() {
      if (libraryLoaded) nativeDispose()
    }
  }

  @JvmStatic private external fun nativeInstall(imagePng: ByteArray): Boolean
  @JvmStatic private external fun nativeUpdate(imagePng: ByteArray, kind: String, count: Int, goals: Array<String>, phases: Array<String>): Boolean
  @JvmStatic private external fun nativeDispose()
}

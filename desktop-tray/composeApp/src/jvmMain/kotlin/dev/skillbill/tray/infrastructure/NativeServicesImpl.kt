package dev.skillbill.tray.infrastructure

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.notification
import dev.skillbill.tray.application.NativeServices
import dev.skillbill.tray.core.domain.model.WorkIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.awt.SystemTray

class NativeServicesImpl : NativeServices {
  override suspend fun hasSystemTray(): Boolean = SystemTray.isSupported().also { supported ->
    if (!supported) RuntimeDiagnostics.record("system_tray_unavailable")
  }

  override fun notify(title: String, body: String, identity: WorkIdentity, onClick: (WorkIdentity) -> Unit) = notifyAuthorized(title, body, identity, { send -> send() }, onClick)

  override fun notifyAuthorized(title: String, body: String, identity: WorkIdentity, authorizeAndSend: (() -> Unit) -> Unit, onClick: (WorkIdentity) -> Unit) {
    Thread({
      try {
        NotificationManager.initialize()
        authorizeAndSend { notification(title = title.take(120), message = body.take(240), onActivated = { onClick(identity) }).send() }
      } catch (_: Exception) {
        RuntimeDiagnostics.record("notification_failed")
      }
    }, "skillbill-notification").apply { isDaemon = true }.start()
  }

  override fun setStartAtLogin(enabled: Boolean): Boolean = try {
    if (os().contains("mac") || os().contains("win")) {
      (if (enabled) AutoLaunch.enable() else AutoLaunch.disable()) in setOf(AutoLaunchResult.OK, AutoLaunchResult.UNCHANGED)
    } else {
      setXdgAutostart(enabled)
    }
  } catch (_: Exception) {
    RuntimeDiagnostics.record("login_start_failed")
    false
  }

  private fun setXdgAutostart(enabled: Boolean): Boolean {
    val path = Path.of(System.getProperty("user.home"), ".config/autostart/skill-bill-tray.desktop")
    if (!enabled) return Files.deleteIfExists(path) || !Files.exists(path)
    val launcher = System.getenv("APPIMAGE") ?: System.getProperty("jpackage.app-path")
    val app = launcher?.let { Path.of(it) }
    if (app == null || !Files.isExecutable(app)) {
      RuntimeDiagnostics.record("xdg_launcher_unavailable")
      return false
    }
    return try {
      Files.createDirectories(path.parent)
      val escaped = app.toString().replace("\\", "\\\\").replace("\"", "\\\"")
      Files.writeString(path, "[Desktop Entry]\nType=Application\nName=Skill Bill\nExec=\"$escaped\"\nTerminal=false\nX-GNOME-Autostart-enabled=true\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
      true
    } catch (_: Exception) {
      RuntimeDiagnostics.record("xdg_autostart_write_failed")
      false
    }
  }

  private fun os() = System.getProperty("os.name").lowercase()
}

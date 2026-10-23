package dev.skillbill.tray.infrastructure

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.notification
import dev.skillbill.tray.application.NativeServices
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.ui.RepositoryNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.awt.SystemTray
import java.util.concurrent.TimeUnit

class NativeServicesImpl : NativeServices, RepositoryNavigation {
  override suspend fun openRepository(root: String, branch: String?): Outcome<Unit> = navigate(
    "Could not open the repository in your browser. Check its origin remote and default browser.",
  ) {
    val remote = runCommand(listOf("git", "-C", root, "remote", "get-url", "origin"))
    openLocation(RepositoryWebLinks.url(remote, branch), directory = false)
  }

  override suspend fun openDirectory(root: String): Outcome<Unit> = navigate("Could not open the checkout directory. Check that it still exists.") {
    val directory = Path.of(root).toAbsolutePath()
    require(Files.isDirectory(directory))
    openLocation(directory.toUri(), directory = true)
  }

  override suspend fun openFile(path: String): Outcome<Unit> = navigate("Could not open the file. Check that it still exists.") {
    val file = Path.of(path).toFile()
    require(file.isAbsolute && file.isFile)
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
      Desktop.getDesktop().open(file)
    } else {
      val os = os()
      val target = file.path
      runCommand(
        when {
          os.contains("linux") -> listOf("xdg-open", target)
          os.contains("mac") -> listOf("open", target)
          else -> listOf("cmd", "/c", "start", "", target)
        },
      )
    }
  }

  private suspend fun navigate(failure: String, action: () -> Unit): Outcome<Unit> = try {
    runInterruptible(Dispatchers.IO) { action() }
    Outcome.Value(Unit)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    RuntimeDiagnostics.record("repository_navigation_failed")
    Outcome.Failure(Problem(ProblemCode.UNKNOWN, failure))
  }

  private fun openLocation(uri: URI, directory: Boolean) {
    if (os().contains("linux")) {
      runCommand(listOf("xdg-open", uri.toASCIIString()))
    } else {
      val desktop = Desktop.getDesktop()
      if (directory) desktop.open(Path.of(uri).toFile()) else desktop.browse(uri)
    }
  }

  private fun runCommand(argv: List<String>): String {
    val process = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
      check(process.waitFor(5, TimeUnit.SECONDS)) { "Repository navigation command timed out." }
      check(process.exitValue() == 0) { "Repository navigation command failed." }
      return process.inputStream.bufferedReader().use { it.readText().trim() }
    } finally {
      if (process.isAlive) process.destroyForcibly()
      process.inputStream.close()
      process.errorStream.close()
      process.outputStream.close()
    }
  }

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

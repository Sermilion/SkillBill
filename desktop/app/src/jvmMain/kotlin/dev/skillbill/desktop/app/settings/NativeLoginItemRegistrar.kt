package dev.skillbill.desktop.app.settings

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.feature.settings.LoginItemRegistrar
import dev.skillbill.desktop.feature.settings.LoginItemResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class NativeLoginItemRegistrar(private val host: HostEnvironment, private val diagnostics: Diagnostics) : LoginItemRegistrar {
  override suspend fun setEnabled(enabled: Boolean): LoginItemResult = try {
    runInterruptible(Dispatchers.IO) { update(enabled) }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    diagnostics.record("login_start_failed")
    LoginItemResult.Failed("Start at login could not be changed.")
  }

  private fun update(enabled: Boolean): LoginItemResult = when {
    os().contains("mac") || os().contains("win") -> when (if (enabled) AutoLaunch.enable() else AutoLaunch.disable()) {
      AutoLaunchResult.OK, AutoLaunchResult.UNCHANGED -> LoginItemResult.Applied
      AutoLaunchResult.BLOCKED_BY_USER -> failed("The system blocked start at login. Allow Skill Bill in your login items settings.")
      AutoLaunchResult.BLOCKED_BY_POLICY -> failed("A system policy blocks start at login on this machine.")
      AutoLaunchResult.UNSUPPORTED -> failed("Start at login is not supported on this system.")
      AutoLaunchResult.ERROR -> failed("Start at login could not be changed.")
    }
    os().contains("linux") -> updateXdg(enabled)
    else -> failed("Start at login is not supported on this system.")
  }

  private fun updateXdg(enabled: Boolean): LoginItemResult {
    val path = Path.of(host.userHome, ".config/autostart/skill-bill-desktop.desktop")
    if (!enabled) {
      return try {
        Files.deleteIfExists(path)
        LoginItemResult.Applied
      } catch (_: Exception) {
        diagnostics.record("xdg_autostart_write_failed")
        LoginItemResult.Failed("Could not update the autostart entry in ~/.config/autostart.")
      }
    }
    val launcher = host.variables["APPIMAGE"] ?: System.getProperty("jpackage.app-path")
    val app = launcher?.let { Path.of(it) }
    if (app == null || !Files.isExecutable(app)) {
      diagnostics.record("xdg_launcher_unavailable")
      return LoginItemResult.Failed("Start at login needs the installed app. This copy has no launcher to register.")
    }
    return try {
      Files.createDirectories(path.parent)
      val escaped = app.toString().replace("\\", "\\\\").replace("\"", "\\\"")
      Files.writeString(
        path,
        "[Desktop Entry]\nType=Application\nName=Skill Bill\nExec=\"$escaped\"\nTerminal=false\nX-GNOME-Autostart-enabled=true\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
      )
      LoginItemResult.Applied
    } catch (_: Exception) {
      diagnostics.record("xdg_autostart_write_failed")
      LoginItemResult.Failed("Could not update the autostart entry in ~/.config/autostart.")
    }
  }

  private fun failed(reason: String): LoginItemResult.Failed {
    diagnostics.record("login_start_failed")
    return LoginItemResult.Failed(reason)
  }

  private fun os() = System.getProperty("os.name").lowercase()
}

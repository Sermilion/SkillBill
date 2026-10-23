package skillbill.infrastructure.host.system

import me.tatarka.inject.annotations.Inject
import skillbill.ports.editor.FileEditorLauncherPort
import skillbill.ports.system.HostPlatformPort
import java.io.IOException
import java.nio.file.Path

@Inject
class ProcessFileEditorLauncher(
  private val platform: HostPlatformPort,
) : FileEditorLauncherPort {
  override fun open(
    path: Path,
    environment: Map<String, String>,
  ): Boolean {
    val target = path.toString()
    val configured =
      listOf("VISUAL", "EDITOR")
        .mapNotNull { name -> environment[name]?.trim()?.takeIf(String::isNotEmpty) }
        .map { value -> editorArgv(value, target) }
    return configured.any { argv -> launch(argv) !in SHELL_LAUNCH_FAILURES } || launch(osDefaultOpener() + target) == 0
  }

  private fun editorArgv(
    editor: String,
    target: String,
  ): List<String> =
    if (isWindows()) {
      editor.split(WHITESPACE) + target
    } else {
      listOf("sh", "-c", "$editor \"\$@\"", "sh", target)
    }

  private fun isWindows(): Boolean {
    val os = platform.osName.lowercase()
    return "mac" !in os && "win" in os
  }

  private fun osDefaultOpener(): List<String> {
    val os = platform.osName.lowercase()
    return when {
      "mac" in os -> listOf("open")
      "win" in os -> listOf("cmd", "/c", "start", "")
      else -> listOf("xdg-open")
    }
  }

  private fun launch(argv: List<String>): Int? =
    try {
      ProcessBuilder(argv).inheritIO().start().waitFor()
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }

  private companion object {
    val WHITESPACE = Regex("\\s+")
    const val SHELL_NOT_EXECUTABLE = 126
    const val SHELL_COMMAND_NOT_FOUND = 127
    val SHELL_LAUNCH_FAILURES = setOf<Int?>(null, SHELL_NOT_EXECUTABLE, SHELL_COMMAND_NOT_FOUND)
  }
}

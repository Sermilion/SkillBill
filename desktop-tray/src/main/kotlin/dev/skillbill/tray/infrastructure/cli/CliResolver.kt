package dev.skillbill.tray.infrastructure.cli

import kotlinx.coroutines.runBlocking
import java.io.File

class CliResolver(
  private val loginShellRunner: ProcessRunner = ProcessRunner(maxStdoutBytes = 128 * 1024),
  private val environment: Map<String, String> = System.getenv(),
  private val home: String = System.getProperty("user.home"),
  private val loginPathProvider: (() -> String?)? = null,
  private val windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
) {
  private val discoveredLoginPath by lazy {
    if (loginPathProvider == null) loginPath() else loginPathProvider.invoke()
  }

  fun resolve(override: String?): List<String>? {
    if (!override.isNullOrBlank()) return executableCandidates(override).firstOrNull(::isExecutableCandidate)?.let(::listOf)
    val candidates = linkedSetOf<String>()
    environment["PATH"].orEmpty().split(File.pathSeparator).filter(String::isNotBlank).forEach { candidates += executableCandidates(File(it, "skill-bill").path) }
    discoveredLoginPath?.split(File.pathSeparator).orEmpty().filter(String::isNotBlank).forEach { candidates += executableCandidates(File(it, "skill-bill").path) }
    environment["SKILL_BILL_BIN_DIR"]?.let { dir -> executableCandidates(File(dir, "skill-bill").path).forEach(candidates::add) }
    File(home, ".local/bin/skill-bill").let { executableCandidates(it.path).forEach(candidates::add) }
    return candidates.firstOrNull(::isExecutableCandidate)?.let(::listOf)
  }

  private fun loginPath(): String? {
    val shell = System.getenv("SHELL") ?: return null.also { RuntimeDiagnostics.record("login_shell_unavailable") }
    val result = runBlocking { loginShellRunner.run(listOf(shell, "-lc", "printf %s \"\$PATH\"")) }
    return (result as? ProcessResult.Success)?.output?.takeIf(String::isNotBlank)
      ?: null.also { RuntimeDiagnostics.record("login_path_discovery_failed") }
  }

  private fun executableCandidates(path: String): List<String> = if (windows) listOf(path, "$path.exe", "$path.bat", "$path.cmd") else listOf(path)
  private fun isExecutableCandidate(path: String) = File(path).isFile && (windows || File(path).canExecute())
}

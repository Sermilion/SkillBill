package dev.skillbill.desktop.core.data.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop temp root: [configured] when it is an existing directory other than the shared `/tmp`, else `<state>/scratch/jvm`,
 * where `<state>` is `SKILL_BILL_STATE_DIR` when set and not blank, else `~/.skill-bill`. When that directory cannot be created, the
 * configured path is returned so startup carries on.
 */
fun desktopTempRoot(configured: String?, variables: Map<String, String>, userHome: String): Path {
  val configuredPath = configured?.takeIf(String::isNotBlank)?.let(Path::of)
  if (configuredPath != null && Files.isDirectory(configuredPath) && !isSharedTemp(configuredPath)) return configuredPath
  val state = variables["SKILL_BILL_STATE_DIR"]?.takeIf(String::isNotBlank)?.let(Path::of) ?: Path.of(userHome, ".skill-bill")
  val scratch = state.resolve("scratch").resolve("jvm")
  return try {
    Files.createDirectories(scratch)
  } catch (failure: IOException) {
    System.err.println("Skill Bill could not create its temp directory $scratch: ${failure.message}")
    Path.of(configured.orEmpty())
  }
}

/** The only desktop reader of `java.io.tmpdir` and the environment for this purpose. Resolves per call so an unavailable root can recover. */
fun currentDesktopTempRoot(): Path =
  desktopTempRoot(System.getProperty("java.io.tmpdir"), System.getenv(), System.getProperty("user.home"))

private fun isSharedTemp(path: Path): Boolean = try {
  path.toRealPath() == Path.of("/tmp").toRealPath()
} catch (_: IOException) {
  // Windows has no /tmp, so nothing there can be the shared root.
  false
}

package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Properties

internal data class QaAndroidTools(val adb: Path, val emulator: Path?)

internal class AndroidQaSdkLocator(private val host: HostEnvironment, private val diagnostics: Diagnostics) {
  fun locate(repositoryRoots: List<String>): QaAndroidTools? {
    val roots = sequence {
      host.variables["ANDROID_HOME"]?.takeIf(String::isNotBlank)?.let(::pathOrNull)?.let { yield(it) }
      host.variables["ANDROID_SDK_ROOT"]?.takeIf(String::isNotBlank)?.let(::pathOrNull)?.let { yield(it) }
      repositoryRoots.distinct().forEach { root -> localSdk(root)?.let { yield(it) } }
    }.map { it.toAbsolutePath().normalize() }.distinct()
    roots.forEach { root ->
      val adb = executable(root.resolve("platform-tools").resolve(executableName("adb")))
      if (adb != null) return QaAndroidTools(adb, executable(root.resolve("emulator").resolve(executableName("emulator"))))
      diagnostics.record("desktop_qa_sdk_adb_unavailable")
    }
    return pathExecutable("adb")?.let {
      diagnostics.record("desktop_qa_adb_path_fallback")
      QaAndroidTools(it, pathExecutable("emulator"))
    } ?: run {
      diagnostics.record("desktop_qa_adb_missing")
      null
    }
  }

  private fun localSdk(repository: String): Path? {
    val root = try {
      Path.of(repository).toAbsolutePath().normalize()
    } catch (_: InvalidPathException) {
      diagnostics.record("desktop_qa_repository_root_invalid")
      return null
    }
    val file = root.resolve("local.properties")
    if (!Files.isRegularFile(file)) return null
    val properties = Properties()
    try {
      Files.newBufferedReader(file).use(properties::load)
    } catch (_: IOException) {
      diagnostics.record("desktop_qa_local_properties_unavailable")
      return null
    } catch (_: IllegalArgumentException) {
      diagnostics.record("desktop_qa_local_properties_invalid")
      return null
    }
    val value = properties.getProperty("sdk.dir")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val path = try {
      Path.of(value)
    } catch (_: InvalidPathException) {
      diagnostics.record("desktop_qa_local_properties_invalid")
      return null
    }
    return if (path.isAbsolute) path else root.resolve(path).normalize()
  }

  private fun executable(path: Path): Path? = path.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }

  private fun pathExecutable(name: String): Path? = host.variables["PATH"].orEmpty()
    .split(java.io.File.pathSeparator)
    .asSequence()
    .filter(String::isNotBlank)
    .mapNotNull { pathOrNull(it)?.resolve(executableName(name)) }
    .mapNotNull(::executable)
    .firstOrNull()

  private fun executableName(name: String): String = if (java.io.File.separatorChar == '\\') "$name.exe" else name

  private fun pathOrNull(raw: String): Path? = try {
    Path.of(raw)
  } catch (_: InvalidPathException) {
    diagnostics.record("desktop_qa_sdk_path_invalid")
    null
  }
}

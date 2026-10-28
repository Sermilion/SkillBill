package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.data.identity.BuildCompatibility
import dev.skillbill.desktop.core.data.identity.BuildIdentityRecord
import dev.skillbill.desktop.core.data.identity.assessBuildCompatibility
import dev.skillbill.desktop.core.domain.model.RuntimeAvailability
import dev.skillbill.desktop.core.domain.model.RuntimeBuildProblem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The gate's verdict plus the installed runtime's jars, which the launcher puts on the app class loader when available. */
data class RuntimeInstallation(val availability: RuntimeAvailability, val runtimeJars: List<Path>)

private sealed interface RecordRead {
  data object Missing : RecordRead

  data object Unreadable : RecordRead

  data class Found(val record: BuildIdentityRecord) : RecordRead
}

/**
 * Compares the installed runtime's build record under [runtimeRoot] with this app's record at [appRecordPath]. It reads two files
 * and lists one directory; it starts no process, opens no database and loads no runtime class.
 */
class InstalledRuntimeBuildGate(private val runtimeRoot: Path, private val appRecordPath: Path) {
  fun check(): RuntimeInstallation {
    val runtimeDirectory = runtimeRoot.resolve("runtime-cli")
    val runtimeJars = runtimeJars(runtimeDirectory.resolve("lib"))
    val installed = read(runtimeDirectory.resolve(BuildIdentityRecord.FILE_NAME))
    val app = read(appRecordPath)
    val installedRecord = (installed as? RecordRead.Found)?.record
    val appRecord = (app as? RecordRead.Found)?.record
    val reinstallCommand = BuildIdentityRecord.reinstallCommand(installedRecord, appRecord)
    val problem = when {
      runtimeJars.isEmpty() || installed == RecordRead.Missing -> RuntimeBuildProblem.NotInstalled(reinstallCommand)
      installedRecord == null -> RuntimeBuildProblem.Unreadable(reinstallCommand, installedIdentity = null)
      appRecord == null -> RuntimeBuildProblem.Unreadable(reinstallCommand, installedRecord.display())
      // read_schema equality is stricter than assessBuildCompatibility: the app reads the store at exactly the runtime's schema.
      installedRecord.readSchema == appRecord.readSchema &&
        assessBuildCompatibility(installedRecord, appRecord) == BuildCompatibility.SUPPORTED ->
        return RuntimeInstallation(RuntimeAvailability.Available, runtimeJars)
      else -> RuntimeBuildProblem.Mismatched(reinstallCommand, installedRecord.display(), appRecord.display())
    }
    return RuntimeInstallation(RuntimeAvailability.Unavailable(problem), runtimeJars)
  }

  private fun runtimeJars(lib: Path): List<Path> = if (!Files.isDirectory(lib)) emptyList() else {
    try {
      Files.list(lib).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.sorted().toList() }
    } catch (_: IOException) {
      emptyList()
    }
  }

  private fun read(file: Path): RecordRead = if (!Files.exists(file)) RecordRead.Missing else {
    try {
      BuildIdentityRecord.parse(Files.readString(file))?.let(RecordRead::Found) ?: RecordRead.Unreadable
    } catch (_: IOException) {
      RecordRead.Unreadable
    }
  }
}

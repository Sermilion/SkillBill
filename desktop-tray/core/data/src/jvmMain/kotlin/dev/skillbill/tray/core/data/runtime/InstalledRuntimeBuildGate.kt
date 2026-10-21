package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.data.identity.BuildIdentityRecord
import dev.skillbill.tray.core.data.session.RuntimeBuildGate
import dev.skillbill.tray.core.domain.model.RuntimeBuildProblem
import dev.skillbill.tray.core.domain.model.RuntimeBuildReason
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val UNKNOWN_DESKTOP_BUILD = "unknown"

private sealed interface RecordRead {
  data object Missing : RecordRead

  data object Unreadable : RecordRead

  data class Found(val record: BuildIdentityRecord) : RecordRead
}

/**
 * Compares the installed runtime's build record with the one packaged into this desktop. Both files are read on every check,
 * so a reinstall is seen by the next selection; nothing here starts a process or opens a database.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class InstalledRuntimeBuildGate(private val host: HostEnvironment) : RuntimeBuildGate {
  override fun check(): RuntimeBuildProblem? {
    val desktop = (host.appResourcesDirectory?.let { read(Path.of(it, BuildIdentityRecord.FILE_NAME)) } as? RecordRead.Found)?.record
    val runtime = runtimeInstallRoot(host.variables, host.userHome).resolve("runtime-cli")
    val installed = if (Files.isDirectory(runtime)) read(runtime.resolve(BuildIdentityRecord.FILE_NAME)) else null
    val installedRecord = (installed as? RecordRead.Found)?.record
    val reason = when {
      installed == null -> RuntimeBuildReason.NOT_INSTALLED
      installed == RecordRead.Missing -> RuntimeBuildReason.MISSING
      installedRecord == null || desktop == null -> RuntimeBuildReason.UNREADABLE
      installedRecord.sameBuildAs(desktop) -> return null
      else -> RuntimeBuildReason.MISMATCH
    }
    return RuntimeBuildProblem(
      reason = reason,
      installedIdentity = installedRecord?.display(),
      desktopIdentity = desktop?.display() ?: UNKNOWN_DESKTOP_BUILD,
      reinstallCommand = BuildIdentityRecord.reinstallCommand(installedRecord, desktop),
    )
  }

  private fun read(file: Path): RecordRead = if (!Files.exists(file)) RecordRead.Missing else {
    try {
      BuildIdentityRecord.parse(Files.readString(file))?.let(RecordRead::Found) ?: RecordRead.Unreadable
    } catch (_: IOException) {
      RecordRead.Unreadable
    }
  }
}

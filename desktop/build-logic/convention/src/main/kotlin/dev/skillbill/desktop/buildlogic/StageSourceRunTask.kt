package dev.skillbill.desktop.buildlogic

import java.io.File
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Stages the same-checkout runtime image and app resources for `runFromSource`, stamping both records with one fresh run id. */
@DisableCachingByDefault(because = "Every launch needs a fresh install run id.")
abstract class StageSourceRunTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val runtimeInstall: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val appResources: DirectoryProperty

  @get:Internal
  abstract val checkoutRoot: DirectoryProperty

  @get:Internal
  abstract val outputRoot: DirectoryProperty

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun stage() {
    SourceRunStaging.stage(
      checkoutRoot.get().asFile,
      runtimeInstall.get().asFile,
      appResources.get().asFile,
      outputRoot.get().asFile,
      "source-run-${UUID.randomUUID()}",
    )
  }
}

internal object SourceRunStaging {
  private const val RECORD = "build-identity.properties"
  private const val RUN_ID_KEY = "install_run_id="

  fun stage(checkoutRoot: File, runtimeInstall: File, appResources: File, outputRoot: File, runId: String) {
    SourceRunPreparation.validateStaging(checkoutRoot, outputRoot, runtimeInstall, appResources)
    requireFile(runtimeInstall.resolve(RECORD))
    requireFile(appResources.resolve(RECORD))
    val runtimeTarget = outputRoot.resolve("runtime").also(SourceRunPreparation::recreate).resolve("runtime-cli")
    val resourcesTarget = outputRoot.resolve("app-resources").also(SourceRunPreparation::recreate)
    runtimeInstall.copyRecursively(runtimeTarget)
    appResources.copyRecursively(resourcesTarget)
    val lib = runtimeTarget.resolve("lib")
    if (lib.listFiles { file -> file.isFile && file.extension == "jar" }.isNullOrEmpty()) {
      throw GradleException("No runtime jar in ${lib.absolutePath}")
    }
    stampRunId(runtimeTarget.resolve(RECORD), runId)
    stampRunId(resourcesTarget.resolve(RECORD), runId)
  }

  private fun requireFile(file: File) {
    if (!file.isFile) throw GradleException("Missing build identity record: ${file.absolutePath}")
  }

  // Splitting on "\n" keeps every other line, including any "\r", byte-identical.
  private fun stampRunId(record: File, runId: String) {
    val text = record.readText()
    val lines = text.split("\n")
    val index = lines.indexOfFirst { it.startsWith(RUN_ID_KEY) }
    record.writeText(
      if (index >= 0) {
        lines.toMutableList().apply { this[index] = "$RUN_ID_KEY$runId" }.joinToString("\n")
      } else {
        val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        "$text$separator$RUN_ID_KEY$runId\n"
      },
    )
  }
}

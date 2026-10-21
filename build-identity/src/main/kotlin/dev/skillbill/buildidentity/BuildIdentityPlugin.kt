package dev.skillbill.buildidentity

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

const val WRITE_BUILD_IDENTITY_TASK = "writeBuildIdentity"

private const val UNKNOWN_COMMIT = "unknown"

private class SourceState(val commit: String, val dirty: Boolean)

class BuildIdentityPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register<WriteBuildIdentityTask>(WRITE_BUILD_IDENTITY_TASK) {
      group = "build"
      description = "Write the build identity record for this checkout."
      val source = target.sourceState()
      commit.set(source.commit)
      dirty.set(source.dirty)
      releaseTag.set(target.providers.environmentVariable("RELEASE_VERSION").orElse(""))
      val runId = target.providers.environmentVariable("SKILL_BILL_INSTALL_RUN_ID").orElse("")
      installRunId.set(if (source.dirty) runId else target.provider { "" })
      record.set(target.layout.buildDirectory.file("build-identity/build-identity.properties"))
    }
  }
}

private fun Project.sourceState(): SourceState {
  val head = gitOutput("rev-parse", "HEAD")?.trim()
  val status = gitOutput("status", "--porcelain")
  if (head.isNullOrEmpty() || status == null) return SourceState(UNKNOWN_COMMIT, dirty = true)
  return SourceState(head, dirty = status.isNotBlank())
}

private fun Project.gitOutput(vararg arguments: String): String? {
  val checkout = rootDir
  val command = listOf("git") + arguments
  val output =
    providers.exec {
      commandLine(command)
      workingDir(checkout)
      isIgnoreExitValue = true
    }
  return try {
    val exitValue = output.result.get().exitValue
    if (exitValue == 0) {
      output.standardOutput.asText.get()
    } else {
      logger.warn(unknownSourceMessage(command, "exited with $exitValue"))
      null
    }
  } catch (expectedGitFailure: Exception) {
    logger.warn(unknownSourceMessage(command, expectedGitFailure.toString()), expectedGitFailure)
    null
  }
}

private fun unknownSourceMessage(
  command: List<String>,
  cause: String,
): String =
  "Cannot read the source state (${command.joinToString(" ")} $cause). The build identity records " +
    "commit=$UNKNOWN_COMMIT and dirty=true, so it only matches a record from the same install run."

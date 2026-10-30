package skillbill.infrastructure.skills.install.apply

import skillbill.config.model.RepoLocalConfigKey
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.host.system.FileConfigDocumentLocks
import skillbill.infrastructure.host.system.configDocumentIoException
import skillbill.infrastructure.skills.rethrowIfCooperativeCancellationOrInterruption
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.model.toPath
import skillbill.ports.projectstore.ProjectStorePort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun applyRepoLocalConfigScaffold(
  plan: InstallPlan,
  projectStore: ProjectStorePort,
  warnings: MutableList<InstallApplyIssue>,
  failures: MutableList<InstallApplyIssue>,
) {
  val repoRoot = plan.request.repoRoot.toPath().toAbsolutePath().normalize()
  scaffoldStep(InstallApplyIssueKind.REPO_LOCAL_CONFIG_SCAFFOLD_FAILED, warnings, failures) {
    writeDefaultConfigIfAbsent(projectStore.resolve(repoRoot).configPath, legacyConfigPath(repoRoot))
  }
  scaffoldStep(InstallApplyIssueKind.REPO_LOCAL_CONFIG_SCAFFOLD_FAILED, warnings, failures) {
    appendGitignoreEntryIfAbsent(repoRoot)
  }
}

private inline fun scaffoldStep(
  kind: InstallApplyIssueKind,
  warnings: MutableList<InstallApplyIssue>,
  failures: MutableList<InstallApplyIssue>,
  step: () -> Path,
) {
  runCatching { step() }.onFailure { error ->
    error.rethrowIfCooperativeCancellationOrInterruption()
    val issue =
      InstallApplyIssue(
        kind = kind,
        message = error.message.orEmpty(),
        causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
      )
    if (error is SkillBillRuntimeException) failures.add(issue) else warnings.add(issue)
  }
}

private fun writeDefaultConfigIfAbsent(
  configPath: Path,
  legacyConfigPath: Path,
): Path {
  return FileConfigDocumentLocks.withLocks(listOf(configPath, legacyConfigPath)) {
    val actualConfigPath = FileConfigDocumentLocks.lockedPath(configPath)
    val actualLegacyConfigPath = FileConfigDocumentLocks.lockedPath(legacyConfigPath)
    try {
      if (configPathAttributes(
          actualConfigPath,
        ) != null || configPathAttributes(actualLegacyConfigPath)?.isRegularFile == true
      ) {
        return@withLocks actualConfigPath
      }
      Files.createDirectories(actualConfigPath.parent)
      Files.writeString(actualConfigPath, defaultConfigContent())
      actualConfigPath
    } catch (error: IOException) {
      throw configDocumentIoException(actualConfigPath, "initialize", error)
    } catch (error: SecurityException) {
      throw configDocumentIoException(actualConfigPath, "initialize", error)
    }
  }
}

private fun configPathAttributes(path: Path): BasicFileAttributes? =
  try {
    Files.readAttributes(path, BasicFileAttributes::class.java)
  } catch (_: NoSuchFileException) {
    null
  } catch (error: IOException) {
    throw configDocumentIoException(path, "inspect", error)
  } catch (error: SecurityException) {
    throw configDocumentIoException(path, "inspect", error)
  }

private fun legacyConfigPath(repoRoot: Path): Path =
  repoRoot.resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY).resolve(REPO_LOCAL_CONFIG_FILE_NAME)

private fun defaultConfigContent(): String =
  RepoLocalConfigKey.entries
    .joinToString(separator = "\n", postfix = "\n") { key -> "${key.key}: ${key.builtinDefault}" }

private fun appendGitignoreEntryIfAbsent(repoRoot: Path): Path {
  val gitignorePath = repoRoot.resolve(".gitignore")
  val existing = if (Files.exists(gitignorePath)) Files.readString(gitignorePath) else ""
  val trimmedLines = existing.lineSequence().map { line -> line.trim() }.toSet()
  val hasLegacyIgnore = GITIGNORE_LEGACY_ENTRY in trimmedLines
  if (hasLegacyIgnore) {
    return gitignorePath
  }
  val linesToAppend =
    listOf(
      GITIGNORE_RUNTIME_STATE,
      GITIGNORE_CONFIG_EXCEPTION,
      GITIGNORE_PHASES_DIR_EXCEPTION,
      GITIGNORE_PHASES_FILES_EXCEPTION,
    ).filter { line -> line !in trimmedLines }
  if (linesToAppend.isEmpty()) {
    return gitignorePath
  }
  Files.writeString(gitignorePath, appendLines(existing, linesToAppend))
  return gitignorePath
}

private fun appendLines(
  existing: String,
  lines: List<String>,
): String =
  buildString {
    append(existing)
    if (existing.isNotEmpty() && !existing.endsWith("\n")) {
      append("\n")
    }
    lines.forEach { line ->
      append(line)
      append("\n")
    }
  }

private const val REPO_LOCAL_CONFIG_FILE_NAME = "config.yaml"
private const val GITIGNORE_RUNTIME_STATE = ".skill-bill/**"
private const val GITIGNORE_CONFIG_EXCEPTION = "!.skill-bill/config.yaml"
private const val GITIGNORE_PHASES_DIR_EXCEPTION = "!.skill-bill/phases/"
private const val GITIGNORE_PHASES_FILES_EXCEPTION = "!.skill-bill/phases/**"
private const val GITIGNORE_LEGACY_ENTRY = "/.skill-bill/"

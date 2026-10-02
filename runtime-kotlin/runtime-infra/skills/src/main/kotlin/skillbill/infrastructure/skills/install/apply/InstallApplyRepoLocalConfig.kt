package skillbill.infrastructure.skills.install.apply

import skillbill.config.model.RepoLocalConfigKey
import skillbill.error.core.failureCodeLabel
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.Path

internal fun applyRepoLocalConfigScaffold(
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
) {
  val repoRoot = plan.request.repoRoot.toPath().toAbsolutePath().normalize()
  scaffoldStep(InstallApplyIssueKind.REPO_LOCAL_CONFIG_SCAFFOLD_FAILED, warnings) {
    writeDefaultConfigIfAbsent(repoRoot)
  }
  scaffoldStep(InstallApplyIssueKind.REPO_LOCAL_CONFIG_SCAFFOLD_FAILED, warnings) {
    appendGitignoreEntryIfAbsent(repoRoot)
  }
}

private inline fun scaffoldStep(
  kind: InstallApplyIssueKind,
  warnings: MutableList<InstallApplyIssue>,
  step: () -> Path,
) {
  runCatching { step() }.onFailure { error ->
    warnings.add(
      InstallApplyIssue(
        kind = kind,
        message = error.message.orEmpty(),
        causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
      ),
    )
  }
}

private fun writeDefaultConfigIfAbsent(repoRoot: Path): Path {
  val configPath = repoRoot.resolve(".skill-bill").resolve("config.yaml")
  if (Files.exists(configPath)) {
    return configPath
  }
  Files.createDirectories(configPath.parent)
  Files.writeString(configPath, defaultConfigContent())
  return configPath
}

private fun defaultConfigContent(): String =
  RepoLocalConfigKey.entries
    .joinToString(separator = "\n", postfix = "\n") { key -> "${key.key}: ${key.builtinDefault}" }

private fun appendGitignoreEntryIfAbsent(repoRoot: Path): Path {
  val gitignorePath = repoRoot.resolve(".gitignore")
  val existing = if (Files.exists(gitignorePath)) Files.readString(gitignorePath) else ""
  val trimmedLines = existing.lineSequence().map { line -> line.trim() }.toSet()
  val hasModernIgnore = GITIGNORE_RUNTIME_STATE in trimmedLines
  val hasLegacyIgnore = GITIGNORE_LEGACY_ENTRY in trimmedLines
  val hasConfigException = GITIGNORE_CONFIG_EXCEPTION in trimmedLines
  if (hasLegacyIgnore) {
    return gitignorePath
  }
  if (hasModernIgnore && hasConfigException) {
    return gitignorePath
  }
  val linesToAppend =
    buildList {
      if (!hasModernIgnore) add(GITIGNORE_RUNTIME_STATE)
      if (!hasConfigException) add(GITIGNORE_CONFIG_EXCEPTION)
    }
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

private const val GITIGNORE_RUNTIME_STATE = ".skill-bill/**"
private const val GITIGNORE_CONFIG_EXCEPTION = "!.skill-bill/config.yaml"
private const val GITIGNORE_LEGACY_ENTRY = "/.skill-bill/"

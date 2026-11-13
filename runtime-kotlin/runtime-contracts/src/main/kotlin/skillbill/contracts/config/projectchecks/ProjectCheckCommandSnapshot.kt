package skillbill.contracts.config.projectchecks

import java.util.Collections

const val PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION: String = "0.1"

object ProjectCheckCommandSnapshotKeys {
  const val CONTRACT_VERSION: String = "contract_version"
  const val PROJECT_ID: String = "project_id"
  const val BUILD_COMMANDS: String = "build_commands"
  const val VALIDATION_COMMANDS: String = "validation_commands"
  const val PARSER_METADATA: String = "parser_metadata"
  const val GRADLE_WRAPPER: String = "gradle_wrapper"
  const val PACK_SLUG: String = "pack_slug"
  const val FINDINGS: String = "findings"
  const val SUPPRESSION_MARKERS: String = "suppression_markers"
  const val FORMAT: String = "format"
  const val ARTIFACT_GLOBS: String = "artifact_globs"
  const val COMPILER_DIAGNOSTICS: String = "compiler_diagnostics"
  const val EXECUTED_WORK: String = "executed_work"
}

data class ProjectCheckCommandParserFindings(
  val format: String,
  val artifactGlobs: List<String>,
  val compilerDiagnosticsFormat: String,
  val executedWorkFormat: String?,
) {
  init {
    require(format.isNotBlank())
    require(artifactGlobs.isNotEmpty() && artifactGlobs.all(String::isNotBlank))
    require(compilerDiagnosticsFormat.isNotBlank())
    require(executedWorkFormat == null || executedWorkFormat.isNotBlank())
  }
}

data class ProjectCheckCommandParserMetadata(
  val packSlug: String,
  val findings: ProjectCheckCommandParserFindings,
  val suppressionMarkers: List<String>,
) {
  init {
    require(packSlug.isNotBlank())
    require(suppressionMarkers.all(String::isNotBlank))
  }
}

class ProjectCheckCommandSnapshot(
  val projectId: String,
  val buildCommands: ProjectCheckCategoryConfig?,
  val validationCommands: ProjectCheckCategoryConfig?,
  val parserMetadata: ProjectCheckCommandParserMetadata?,
  val gradleWrapper: String?,
  val contractVersion: String = PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION,
) {
  val buildCommandList: List<ProjectCheckCommand> =
    Collections.unmodifiableList(buildCommands?.commands.orEmpty().map { it.copy() })
  val validationCommandList: List<ProjectCheckCommand> =
    Collections.unmodifiableList(validationCommands?.commands.orEmpty().map { it.copy() })

  init {
    require(projectId.isNotBlank())
    require(contractVersion == PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION)
  }

  fun canonicalIdentity(): List<Any?> =
    listOf(
      PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION,
      projectId,
      buildCommands?.commands?.map { listOf(it.argv, it.workingDirectory) },
      validationCommands?.commands?.map { listOf(it.argv, it.workingDirectory) },
      gradleWrapper,
      parserMetadata?.let { meta ->
        listOf(
          meta.packSlug,
          meta.findings.format,
          meta.findings.artifactGlobs.sorted(),
          meta.findings.compilerDiagnosticsFormat,
          meta.findings.executedWorkFormat,
          meta.suppressionMarkers.sorted(),
        )
      },
    )

  fun frozen(): ProjectCheckCommandSnapshot =
    ProjectCheckCommandSnapshot(
      projectId = projectId,
      buildCommands = freezeCategory(buildCommands),
      validationCommands = freezeCategory(validationCommands),
      parserMetadata =
        parserMetadata?.let { meta ->
          ProjectCheckCommandParserMetadata(
            meta.packSlug,
            ProjectCheckCommandParserFindings(
              meta.findings.format,
              meta.findings.artifactGlobs.toList(),
              meta.findings.compilerDiagnosticsFormat,
              meta.findings.executedWorkFormat,
            ),
            meta.suppressionMarkers.toList(),
          )
        },
      gradleWrapper = gradleWrapper,
    )

  private fun freezeCategory(category: ProjectCheckCategoryConfig?): ProjectCheckCategoryConfig? =
    category?.let { source ->
      ProjectCheckCategoryConfig(
        source.commands.map { command ->
          ProjectCheckCommand(command.argv.toList(), command.workingDirectory)
        },
      )
    }
}

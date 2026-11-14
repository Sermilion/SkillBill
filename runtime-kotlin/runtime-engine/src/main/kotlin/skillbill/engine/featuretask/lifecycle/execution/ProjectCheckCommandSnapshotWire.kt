package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.JsonCodec
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommandParserMetadata
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshotKeys
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.ports.config.projectchecks.ProjectCheckCommandSnapshotValidator

internal fun encodeProjectCheckCommandSnapshot(snapshot: ProjectCheckCommandSnapshot): Map<String, Any?> =
  linkedMapOf(
    ProjectCheckCommandSnapshotKeys.CONTRACT_VERSION to snapshot.contractVersion,
    ProjectCheckCommandSnapshotKeys.PROJECT_ID to snapshot.projectId,
    ProjectCheckCommandSnapshotKeys.BUILD_COMMANDS to encodeCategory(snapshot.buildCommands),
    ProjectCheckCommandSnapshotKeys.VALIDATION_COMMANDS to encodeCategory(snapshot.validationCommands),
    ProjectCheckCommandSnapshotKeys.PARSER_METADATA to encodeParserMetadata(snapshot.parserMetadata),
    ProjectCheckCommandSnapshotKeys.GRADLE_WRAPPER to snapshot.gradleWrapper,
  )

internal fun decodeProjectCheckCommandSnapshot(
  value: Any?,
  validator: ProjectCheckCommandSnapshotValidator,
  sourceLabel: String,
): ProjectCheckCommandSnapshot? {
  if (value == null) return null
  val document = JsonCodec.valueToJsonString(value)
  return validator.validate(document, sourceLabel)
}

private fun encodeCategory(category: ProjectCheckCategoryConfig?): Any? =
  category?.let {
    mapOf(
      ProjectCheckConfigKeys.COMMANDS to
        it.commands.map { command ->
          mapOf(
            ProjectCheckConfigKeys.ARGV to command.argv,
            ProjectCheckConfigKeys.WORKING_DIRECTORY to command.workingDirectory,
          )
        },
    )
  }

private fun encodeParserMetadata(metadata: ProjectCheckCommandParserMetadata?): Any? =
  metadata?.let { meta ->
    mapOf(
      ProjectCheckCommandSnapshotKeys.PACK_SLUG to meta.packSlug,
      ProjectCheckCommandSnapshotKeys.FINDINGS to
        mapOf(
          ProjectCheckCommandSnapshotKeys.FORMAT to meta.findings.format,
          ProjectCheckCommandSnapshotKeys.ARTIFACT_GLOBS to meta.findings.artifactGlobs,
          ProjectCheckCommandSnapshotKeys.COMPILER_DIAGNOSTICS to
            mapOf(ProjectCheckCommandSnapshotKeys.FORMAT to meta.findings.compilerDiagnosticsFormat),
          ProjectCheckCommandSnapshotKeys.EXECUTED_WORK to
            meta.findings.executedWorkFormat?.let {
              mapOf(ProjectCheckCommandSnapshotKeys.FORMAT to it)
            },
        ),
      ProjectCheckCommandSnapshotKeys.SUPPRESSION_MARKERS to meta.suppressionMarkers,
    )
  }

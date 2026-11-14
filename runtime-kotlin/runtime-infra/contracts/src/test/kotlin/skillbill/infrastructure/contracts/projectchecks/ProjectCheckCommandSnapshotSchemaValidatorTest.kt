package skillbill.infrastructure.contracts.projectchecks

import skillbill.contracts.JsonCodec
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshotKeys
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateFindingsFormat
import kotlin.test.Test
import kotlin.test.assertNull

class ProjectCheckCommandSnapshotSchemaValidatorTest {
  @Test
  fun `parser metadata from a pack without executed work remains valid`() {
    val document =
      JsonCodec.valueToJsonString(
        mapOf(
          ProjectCheckCommandSnapshotKeys.CONTRACT_VERSION to PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION,
          ProjectCheckCommandSnapshotKeys.PROJECT_ID to "cli-fixture",
          ProjectCheckCommandSnapshotKeys.BUILD_COMMANDS to
            mapOf(
              ProjectCheckConfigKeys.COMMANDS to
                listOf(
                  mapOf(
                    ProjectCheckConfigKeys.ARGV to listOf("./gradlew", "compileKotlin"),
                    ProjectCheckConfigKeys.WORKING_DIRECTORY to ".",
                  ),
                ),
            ),
          ProjectCheckCommandSnapshotKeys.VALIDATION_COMMANDS to null,
          ProjectCheckCommandSnapshotKeys.PARSER_METADATA to
            mapOf(
              ProjectCheckCommandSnapshotKeys.PACK_SLUG to "kotlin",
              ProjectCheckCommandSnapshotKeys.FINDINGS to
                mapOf(
                  ProjectCheckCommandSnapshotKeys.FORMAT to ValidationGateFindingsFormat.JUNIT_XML.wireValue,
                  ProjectCheckCommandSnapshotKeys.ARTIFACT_GLOBS to listOf("build/test-results/**/*.xml"),
                  ProjectCheckCommandSnapshotKeys.COMPILER_DIAGNOSTICS to
                    mapOf(
                      ProjectCheckCommandSnapshotKeys.FORMAT to
                        ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT.wireValue,
                    ),
                  ProjectCheckCommandSnapshotKeys.EXECUTED_WORK to null,
                ),
              ProjectCheckCommandSnapshotKeys.SUPPRESSION_MARKERS to emptyList<String>(),
            ),
          ProjectCheckCommandSnapshotKeys.GRADLE_WRAPPER to null,
        ),
      )
    val snapshot = ProjectCheckCommandSnapshotSchemaValidator().validate(document, "cli-fixture")
    assertNull(snapshot.parserMetadata?.findings?.executedWorkFormat)
  }
}

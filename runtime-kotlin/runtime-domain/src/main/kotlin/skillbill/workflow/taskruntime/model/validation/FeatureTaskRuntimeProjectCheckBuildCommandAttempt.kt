package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError

const val PROJECT_CHECK_BUILD_COMMAND_ATTEMPT_CONTRACT_VERSION: String = "0.1"

data class FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry(
  val argv: List<String>,
  val workingDirectory: String,
  val command: String,
  val exitCode: Int,
  val durationMs: Long,
  val outcome: ValidationGateRunOutcome,
  val stdoutExcerpt: String? = null,
) {
  internal fun toArtifactMap(): Map<String, Any?> {
    val values =
      linkedMapOf(
        ProjectCheckConfigKeys.ARGV to argv,
        ProjectCheckConfigKeys.WORKING_DIRECTORY to workingDirectory,
        ValidationEvidencePayloadKeys.COMMAND to command,
        ValidationEvidencePayloadKeys.EXIT_CODE to exitCode,
        ValidationEvidencePayloadKeys.DURATION_MS to durationMs,
        ValidationEvidencePayloadKeys.OUTCOME to outcome.wireValue,
      )
    stdoutExcerpt?.let { excerpt -> values[ValidationEvidencePayloadKeys.STDOUT_EXCERPT] = excerpt }
    return values
  }
}

data class FeatureTaskRuntimeProjectCheckBuildCommandAttempt(
  val listIdentity: String,
  val commands: List<FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry>,
) {
  init {
    require(listIdentity.isNotBlank())
    require(commands.isNotEmpty())
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to PROJECT_CHECK_BUILD_COMMAND_ATTEMPT_CONTRACT_VERSION,
      ValidationEvidencePayloadKeys.LIST_IDENTITY to listIdentity,
      ProjectCheckConfigKeys.COMMANDS to commands.map { it.toArtifactMap() },
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeProjectCheckBuildCommandAttempt {
      if (raw[SharedPayloadKeys.CONTRACT_VERSION] != PROJECT_CHECK_BUILD_COMMAND_ATTEMPT_CONTRACT_VERSION) {
        invalid("Unsupported project check build command attempt contract_version.")
      }
      val listIdentity =
        raw[ValidationEvidencePayloadKeys.LIST_IDENTITY] as? String
          ?: invalid("Project check build command attempt is missing list_identity.")
      val commandsRaw =
        raw[ProjectCheckConfigKeys.COMMANDS] as? List<*>
          ?: invalid("Project check build command attempt is missing commands.")
      return FeatureTaskRuntimeProjectCheckBuildCommandAttempt(
        listIdentity,
        commandsRaw.mapIndexed(::commandEntry),
      )
    }

    private fun commandEntry(
      index: Int,
      value: Any?,
    ): FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry {
      val map =
        value as? Map<*, *>
          ?: invalid("Project check build command attempt command $index must be a mapping.")
      val argv =
        (map[ProjectCheckConfigKeys.ARGV] as? List<*>)
          ?.map { entry ->
            entry as? String ?: invalid("Command argv entry $index must be a string.")
          }
          ?: invalid("Command $index is missing argv.")
      val workingDirectory =
        map[ProjectCheckConfigKeys.WORKING_DIRECTORY] as? String
          ?: invalid("Command $index is missing working_directory.")
      val command =
        map[ValidationEvidencePayloadKeys.COMMAND] as? String
          ?: invalid("Command $index is missing command.")
      val exitCode =
        (map[ValidationEvidencePayloadKeys.EXIT_CODE] as? Number)?.toInt()
          ?: invalid("Command $index is missing exit_code.")
      val durationMs =
        (map[ValidationEvidencePayloadKeys.DURATION_MS] as? Number)?.toLong()
          ?: invalid("Command $index is missing duration_ms.")
      val outcomeWire =
        map[ValidationEvidencePayloadKeys.OUTCOME] as? String
          ?: invalid("Command $index is missing outcome.")
      val outcome =
        ValidationGateRunOutcome.fromWire(outcomeWire)
          ?: invalid("Command $index has an unsupported outcome.")
      return FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry(
        argv = argv,
        workingDirectory = workingDirectory,
        command = command,
        exitCode = exitCode,
        durationMs = durationMs,
        outcome = outcome,
        stdoutExcerpt = map[ValidationEvidencePayloadKeys.STDOUT_EXCERPT] as? String,
      )
    }

    private fun invalid(detail: String): Nothing = throw invalidWorkflowStateSchemaError(detail)
  }
}

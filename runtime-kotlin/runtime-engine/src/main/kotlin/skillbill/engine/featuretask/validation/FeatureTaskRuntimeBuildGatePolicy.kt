package skillbill.engine.featuretask.validation

import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.error.shellcontent.invalidValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateDeclaration

public fun buildGateArgv(
  declaration: ValidationGateDeclaration,
  cyclePhase: ValidationGateCyclePhase,
): List<String> {
  val buildCommand =
    declaration.buildCommand
      ?: throw invalidValidationGateDeclaration(
        "validation_gate.build_command is required for the build phase but absent on the selected pack.",
      )
  val cacheBypassingBuildCommand =
    declaration.cacheBypassingBuildCommand
      ?: throw invalidValidationGateDeclaration(
        "validation_gate.cache_bypassing_build_command is required for the build phase " +
          "but absent on the selected pack.",
      )
  return when (cyclePhase) {
    ValidationGateCyclePhase.INITIAL_DISCOVERY -> buildCommand
    ValidationGateCyclePhase.POST_REPAIR_VERIFY -> cacheBypassingBuildCommand
  }
}

internal object FeatureTaskRuntimeBuildGatePolicy {
  fun selectedBuildCommands(
    admittedSnapshot: ProjectCheckCommandSnapshot?,
    admittedInputs: EffectiveGatePolicyInputs?,
    recordedInputs: EffectiveGatePolicyInputs?,
    declaration: ValidationGateDeclaration?,
    cyclePhase: ValidationGateCyclePhase,
  ): List<ProjectCheckCommand>? {
    admittedSnapshot?.buildCommands?.let { category ->
      return category.commands
    }
    val legacyArgv =
      recordedInputs?.commandArgv(cyclePhase)
        ?: admittedInputs?.commandArgv(cyclePhase)
        ?: declaration?.let {
          when (cyclePhase) {
            ValidationGateCyclePhase.INITIAL_DISCOVERY -> it.buildCommand
            ValidationGateCyclePhase.POST_REPAIR_VERIFY -> it.cacheBypassingBuildCommand
          }
        }
    return legacyArgv?.let { listOf(ProjectCheckCommand(it, ".")) }
  }

  fun commandListIdentity(commands: List<ProjectCheckCommand>): String =
    commands.joinToString(" || ") { "${it.workingDirectory}::${it.argv.joinToString("\u0000")}" }

  fun knownEmptyBuild(snapshot: ProjectCheckCommandSnapshot?): Boolean =
    snapshot?.buildCommands?.commands?.isEmpty() == true

  fun missingAdmittedBuildDetail(snapshot: ProjectCheckCommandSnapshot): String? =
    when {
      knownEmptyBuild(snapshot) ->
        "Required build commands are explicitly empty in the admitted project configuration."
      snapshot.buildCommands == null ->
        "Required build commands were not frozen at admission."
      else -> null
    }
}

package skillbill.engine.featuretask.validation

import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.error.shellcontent.InvalidValidationGateDeclarationError
import skillbill.scaffold.model.ValidationGateDeclaration

internal fun buildGateArgv(
  declaration: ValidationGateDeclaration,
  cyclePhase: ValidationGateCyclePhase,
): List<String> {
  val buildCommand =
    declaration.buildCommand
      ?: throw InvalidValidationGateDeclarationError(
        "validation_gate.build_command is required for the build phase but absent on the selected pack.",
      )
  val cacheBypassingBuildCommand =
    declaration.cacheBypassingBuildCommand
      ?: throw InvalidValidationGateDeclarationError(
        "validation_gate.cache_bypassing_build_command is required for the build phase " +
          "but absent on the selected pack.",
      )
  return when (cyclePhase) {
    ValidationGateCyclePhase.INITIAL_DISCOVERY -> buildCommand
    ValidationGateCyclePhase.POST_REPAIR_VERIFY -> cacheBypassingBuildCommand
  }
}

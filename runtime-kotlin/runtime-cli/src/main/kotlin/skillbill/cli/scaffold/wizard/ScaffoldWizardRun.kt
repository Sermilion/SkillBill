package skillbill.cli.scaffold.wizard

import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.InstallAgentService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.cli.scaffold.payload.NativeScaffoldPayloadRun
import skillbill.cli.scaffold.payload.NativeScaffoldRunOptions
import skillbill.cli.scaffold.payload.completeScaffoldError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.scaffold.model.SkillKind

@Inject
class ScaffoldWizardRun(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val scaffoldCatalogGateway: ScaffoldCatalogGateway,
  private val installAgentService: InstallAgentService,
  private val payloadRun: NativeScaffoldPayloadRun,
) {
  internal fun runWizard(options: NativeScaffoldRunOptions) {
    runCollected(options) { collectScaffoldWizardPayload(state, inputs, scaffoldCatalogGateway) }
  }

  internal fun runAssistedWizard(options: NativeScaffoldRunOptions) {
    runCollected(options) {
      collectAssistedScaffoldWizardPayload(state, inputs, scaffoldCatalogGateway, installAgentService)
    }
  }

  private fun runCollected(
    options: NativeScaffoldRunOptions,
    collect: () -> Map<String, Any?>,
  ) {
    val payload =
      try {
        collect()
      } catch (error: SkillBillRuntimeException) {
        return state.completeScaffoldError(error.message.orEmpty(), options.format)
      } catch (error: IllegalArgumentException) {
        return state.completeScaffoldError(error.message.orEmpty(), options.format)
      }
    payloadRun.runPayload(payload, options)
  }
}

internal fun collectAssistedScaffoldWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  scaffoldCatalogGateway: ScaffoldCatalogGateway,
  installAgentService: InstallAgentService,
): Map<String, Any?> {
  inputs.liveStdout(
    "Skill Bill assisted scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  val kind = normalizeWizardKind(promptRequired(state, inputs, "Kind"))
  val agent =
    promptAssistedAgent(
      state,
      inputs,
      installAgentService.detectAgentTargets(inputs.userHome, inputs.environment).map { target -> target.name },
    )
  inputs.liveStdout(
    "Assisted generator: $agent. Scaffold suggestions are deterministic local defaults; " +
      "agent-backed generation needs a structured scaffold output contract.\n",
  )
  return when (kind) {
    SkillKind.PLATFORM_PACK.wireValue ->
      assistedPlatformPackWizardPayload(state, inputs, scaffoldCatalogGateway.platformPackPresets())
    else -> throw IllegalArgumentException(
      "Assisted mode currently supports platform-pack scaffolds. Use the normal wizard for kind '$kind'.",
    )
  }
}

internal fun collectScaffoldWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  scaffoldCatalogGateway: ScaffoldCatalogGateway,
): Map<String, Any?> {
  inputs.liveStdout(
    "Skill Bill scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  return when (val kind = normalizeWizardKind(promptRequired(state, inputs, "Kind"))) {
    SkillKind.HORIZONTAL.wireValue -> horizontalWizardPayload(state, inputs)
    SkillKind.PLATFORM_PACK.wireValue ->
      platformPackWizardPayload(state, inputs, scaffoldCatalogGateway.platformPackPresets())
    SkillKind.ADD_ON.wireValue -> addOnWizardPayload(state, inputs)
    SkillKind.AGENT_ADDON.wireValue -> agentAddonWizardPayload(state, inputs)
    else -> throw IllegalArgumentException("Unsupported scaffold wizard kind '$kind'.")
  }
}

internal fun horizontalWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
): Map<String, Any?> =
  buildMap {
    putScaffoldBase(SkillKind.HORIZONTAL.wireValue)
    put("name", normalizeBillSkillName(promptRequired(state, inputs, "Skill name")))
    promptOptional(state, inputs, "Description").ifNotBlank { description -> put("description", description) }
  }

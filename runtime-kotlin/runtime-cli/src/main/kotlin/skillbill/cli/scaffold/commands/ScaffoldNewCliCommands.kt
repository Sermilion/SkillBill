package skillbill.cli.scaffold.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.scaffold.payload.CreateAndFillContentArgs
import skillbill.cli.scaffold.payload.NativeScaffoldPayloadRun
import skillbill.cli.scaffold.payload.NativeScaffoldRunOptions
import skillbill.cli.scaffold.payload.NewAddonPayloadArgs
import skillbill.cli.scaffold.payload.completeScaffoldError
import skillbill.cli.scaffold.payload.completeUnsupportedScaffold
import skillbill.cli.scaffold.payload.newAddonPayload
import skillbill.cli.scaffold.payload.retiredInteractiveModeMessage
import skillbill.cli.scaffold.wizard.ScaffoldWizardRun

abstract class NewSkillScaffoldCommand(
  name: String,
  private val state: CliRunState,
  private val payloadRun: NativeScaffoldPayloadRun,
  private val wizardRun: ScaffoldWizardRun,
) : DocumentedCliCommand(name, "Scaffold a new skill from a short wizard or payload file.") {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option(
    "--interactive",
    help = "Run the prompt wizard. This is the default when --payload is omitted.",
  )
    .flag(default = false)
  private val assisted by option(
    "--assisted",
    help = "Run the assisted wizard. It asks for scaffold kind, agent, and the minimum required inputs.",
  )
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    val options = NativeScaffoldRunOptions(dryRun = dryRun, format = format, withExternalAddonOverlay = true)
    if (assisted && payload != null) {
      state.completeScaffoldError("--assisted cannot be combined with --payload.", format)
    } else if (assisted) {
      wizardRun.runAssistedWizard(options)
    } else if (interactive || payload == null) {
      wizardRun.runWizard(options)
    } else {
      payloadRun.runPayloadFile(payload, options)
    }
  }
}

@Inject
class NewSkillCommand(
  state: CliRunState,
  payloadRun: NativeScaffoldPayloadRun,
  wizardRun: ScaffoldWizardRun,
) : NewSkillScaffoldCommand("new-skill", state, payloadRun, wizardRun)

@Inject
class NewCommand(
  state: CliRunState,
  payloadRun: NativeScaffoldPayloadRun,
  wizardRun: ScaffoldWizardRun,
) : NewSkillScaffoldCommand("new", state, payloadRun, wizardRun)

@Inject
class CreateAndFillCommand(
  private val payloadRun: NativeScaffoldPayloadRun,
) : DocumentedCliCommand(
    "create-and-fill",
    "Scaffold one governed skill, then immediately author content.md and validate it.",
  ) {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option("--interactive", help = "Retired in SKILL-32; use --payload instead.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val body by option("--body", help = "Optional authored body to write after scaffolding.")
  private val bodyFile by option(
    "--body-file",
    help = "Optional file path (or '-') to read the authored body from.",
  )
  private val editor by option(
    "--editor",
    help = "Open the scaffolded content.md in \$VISUAL or \$EDITOR.",
  )
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    payloadRun.createAndFill(
      CreateAndFillContentArgs(
        payload = payload,
        interactive = interactive,
        body = body,
        bodyFile = bodyFile,
        editor = editor,
      ),
      NativeScaffoldRunOptions(dryRun = dryRun, format = format, withExternalAddonOverlay = false),
    )
  }
}

@Inject
class NewAddonCommand(
  private val state: CliRunState,
  private val payloadRun: NativeScaffoldPayloadRun,
) : DocumentedCliCommand(
    "new-addon",
    "Create a governed add-on file inside an existing platform pack or external add-on source.",
  ) {
  private val platform by option("--platform", help = "Owning platform slug.")
  private val name by option(
    "--name",
    help = "Add-on slug (without a bill- prefix).",
  )
  private val body by option("--body", help = "Advanced/scripted: complete markdown body to write to the add-on file.")
  private val bodyFile by option(
    "--body-file",
    help = "Advanced/scripted: markdown file to copy into the add-on (or '-').",
  )
  private val addonLocationPath by option(
    "--addon-location-path",
    help = "Optional external add-on source directory. When set, writes <name>.md and addon-manifest.yaml there.",
  )
  private val consumerSkillDirs by option(
    "--consumer-skill-dir",
    help =
      "Advanced/scripted: skill-relative directory to register as an add-on consumer. May be repeated. " +
        "Defaults to the pack baseline code-review skill.",
  ).multiple()
  private val interactive by option("--interactive", help = "Retired in SKILL-32; use explicit options instead.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    if (interactive) {
      state.completeUnsupportedScaffold(
        retiredInteractiveModeMessage(
          "new-addon --interactive",
          "skill-bill new-addon --platform <platform> --name <name>",
        ),
        format,
      )
    } else if (body != null && bodyFile != null) {
      state.completeScaffoldError("--body and --body-file are mutually exclusive.", format)
    } else {
      payloadRun.runPayload(
        newAddonPayload(
          NewAddonPayloadArgs(
            platform = platform,
            name = name,
            body = body,
            bodyFile = bodyFile,
            addonLocationPath = addonLocationPath,
            consumerSkillDirs = consumerSkillDirs,
          ),
          state,
        ),
        NativeScaffoldRunOptions(dryRun = dryRun, format = format, withExternalAddonOverlay = true),
      )
    }
  }
}

package skillbill.cli.learning

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.learning.LearningService
import skillbill.application.learning.model.AddLearningInput
import skillbill.application.learning.model.EditLearningInput
import skillbill.cli.kernel.cli.CliOutput
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.toCliPresentation
import skillbill.cli.kernel.payload.toPayload
import skillbill.cli.model.CliFormat
import skillbill.learnings.model.LearningScope

private val learningScopeChoices = LearningScope.entries.associateBy(LearningScope::wireName)

@Inject
class LearningsQueryCommands(
  listCommand: LearningsListCommand,
  showCommand: LearningsShowCommand,
  resolveCommand: LearningsResolveCommand,
) {
  val commands: List<CliktCommand> = listOf(listCommand, showCommand, resolveCommand)
}

@Inject
class LearningsMutationCommands(
  addCommand: LearningsAddCommand,
  editCommand: LearningsEditCommand,
  disableCommand: LearningsDisableCommand,
  enableCommand: LearningsEnableCommand,
  deleteCommand: LearningsDeleteCommand,
) {
  val commands: List<CliktCommand> = listOf(addCommand, editCommand, disableCommand, enableCommand, deleteCommand)
}

@Inject
class LearningsCommand(
  queryCommands: LearningsQueryCommands,
  mutationCommands: LearningsMutationCommands,
) : DocumentedNoOpCliCommand("learnings", "Manage local review learnings.") {
  init {
    subcommands(queryCommands.commands + mutationCommands.commands)
  }
}

@Inject
class LearningsListCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("list", "List local learning entries.") {
  private val status by option("--status", help = "Learning status filter.").default("all")
  private val format by formatOption()

  override fun run() {
    val result = service.list(status)
    val payload = result.toPayload()
    if (format == CliFormat.JSON) {
      state.complete(payload, format)
    } else {
      state.completeText(CliOutput.learnings(result.toCliPresentation()), payload)
    }
  }
}

@Inject
class LearningsShowCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("show", "Show a single learning entry.") {
  private val id by option("--id", help = "Learning id.").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.show(id).toPayload(), format)
  }
}

@Inject
class LearningsResolveCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("resolve", "Resolve active learnings for a review context.") {
  private val repo by option("--repo", help = "Optional repo scope key.")
  private val skill by option("--skill", help = "Optional review skill name.")
  private val reviewSessionId by option("--review-session-id", help = "Review session id for cross-event grouping.")
  private val format by formatOption()

  override fun run() {
    val result = service.resolve(repo, skill, reviewSessionId)
    val payload = result.toPayload()
    if (format == CliFormat.JSON) {
      state.complete(payload, format)
    } else {
      state.completeText(CliOutput.resolvedLearnings(result.toCliPresentation()), payload)
    }
  }
}

@Inject
class LearningsAddCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("add", "Create a learning from a rejected review finding.") {
  private val scope by option("--scope").choice(learningScopeChoices).default(LearningScope.GLOBAL)
  private val scopeKey by option("--scope-key").default("")
  private val title by option("--title").required()
  private val rule by option("--rule").required()
  private val reason by option("--reason").default("")
  private val fromRun by option("--from-run").required()
  private val fromFinding by option("--from-finding").required()
  private val format by formatOption()

  override fun run() {
    state.complete(
      service.add(AddLearningInput(scope, scopeKey, title, rule, reason, fromRun, fromFinding))
        .toPayload(),
      format,
    )
  }
}

@Inject
class LearningsEditCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("edit", "Edit a local learning entry.") {
  private val id by option("--id").int().required()
  private val scope by option("--scope").choice(learningScopeChoices)
  private val scopeKey by option("--scope-key")
  private val title by option("--title")
  private val rule by option("--rule")
  private val reason by option("--reason")
  private val format by formatOption()

  override fun run() {
    require(listOf(scope, scopeKey, title, rule, reason).any { it != null }) {
      "Learning edit requires at least one field to update."
    }
    state.complete(
      service.edit(EditLearningInput(id, scope, scopeKey, title, rule, reason)).toPayload(),
      format,
    )
  }
}

@Inject
class LearningsDisableCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("disable", "Disable a learning entry.") {
  private val id by option("--id").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.setStatus(id, "disabled").toPayload(), format)
  }
}

@Inject
class LearningsEnableCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("enable", "Enable a disabled learning entry.") {
  private val id by option("--id").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.setStatus(id, "active").toPayload(), format)
  }
}

@Inject
class LearningsDeleteCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("delete", "Delete a learning entry.") {
  private val id by option("--id").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.delete(id).toPayload(), format)
  }
}

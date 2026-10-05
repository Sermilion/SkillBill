package skillbill.cli.scaffold.wizard

import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.error.shellcontent.invalidScaffoldInputError

internal fun promptRequired(
  state: CliRunState,
  inputs: CliRunInputs,
  label: String,
): String {
  val value = promptOptional(state, inputs, label)
  if (value.isBlank()) throw invalidScaffoldInputError("Missing required scaffold wizard value: $label.")
  return value
}

internal fun promptOptional(
  state: CliRunState,
  inputs: CliRunInputs,
  label: String,
): String {
  inputs.liveStdout("$label: ")
  return state.readInputLine()?.trim().orEmpty()
}

internal fun promptDefault(
  state: CliRunState,
  inputs: CliRunInputs,
  label: String,
  default: String,
): String {
  val value = promptOptional(state, inputs, label)
  return value.ifBlank { default }
}

internal fun promptRoutingSignals(
  state: CliRunState,
  inputs: CliRunInputs,
  platform: String,
  hasPreset: Boolean,
): List<String> {
  if (hasPreset) {
    inputs.liveStdout(
      "Built-in routing preset found for '$platform'. Press Enter to use it, or enter comma-separated " +
        "replacement signals such as file extensions, filenames, or directory markers.\n",
    )
    return parseCommaSeparated(promptOptional(state, inputs, "Routing signal override (optional, comma-separated)"))
  }
  inputs.liveStdout(
    "Routing signals tell Skill Bill when to use this platform pack. Enter comma-separated text " +
      "markers that strongly identify the stack in changed files, repo markers, or dependency manifests.\n" +
      "Use file extensions (.kt, .go), filenames (go.mod, package.json), directories (src/main/java), " +
      "dependency coordinates, or language markers.\n",
  )
  val signals = parseCommaSeparated(promptRequired(state, inputs, "Strong routing signals (comma-separated)"))
  if (signals.isEmpty()) {
    throw invalidScaffoldInputError("Missing required scaffold wizard value: Strong routing signals (comma-separated).")
  }
  return signals
}

internal fun promptAssistedAgent(
  state: CliRunState,
  inputs: CliRunInputs,
  detectedAgents: List<String>,
): String {
  val agents = detectedAgents.distinct().sorted()
  if (agents.isEmpty()) {
    inputs.liveStdout("No installed agents detected; using local deterministic assistance.\n")
    return "local"
  }
  inputs.liveStdout(
    "Available agents:\n" +
      agents.mapIndexed { index, agent -> "  ${index + 1}. $agent" }.joinToString(separator = "\n") +
      "\n",
  )
  val selected = promptDefault(state, inputs, "Agent [1]", "1")
  val byNumber = selected.toIntOrNull()?.let { number -> agents.getOrNull(number - 1) }
  val byName = agents.firstOrNull { agent -> agent.equals(selected, ignoreCase = true) }
  return byNumber ?: byName ?: throw invalidScaffoldInputError(
    "Unknown assisted agent '$selected'. Choose one of: ${agents.joinToString(", ")}.",
  )
}

internal fun requiredCommaSeparated(
  state: CliRunState,
  inputs: CliRunInputs,
  label: String,
): List<String> =
  parseCommaSeparated(promptRequired(state, inputs, label)).also { values ->
    if (values.isEmpty()) throw invalidScaffoldInputError("Missing required scaffold wizard value: $label.")
  }

internal fun parseCommaSeparated(value: String): List<String> =
  value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

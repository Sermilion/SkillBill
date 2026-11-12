package skillbill.engine.apprun.model

import skillbill.contracts.apprun.AppRunScriptContract

internal class AppRunScriptOutput(private val listener: AppRunListener) {
  var serial: String? = null
  var failedTask: String? = null
  var candidates: List<String>? = null
  var launched: String? = null
  var failure: String? = null

  fun accept(line: String) {
    listener.onOutput(line)
    val tokens = line.trim().split(Regex("\\s+"))
    if (tokens.size < 2 || tokens[0] != AppRunScriptContract.MARKER_PREFIX) return
    when (tokens[1]) {
      AppRunScriptContract.SERIAL -> serial = tokens.drop(2).joinToString(" ").ifBlank { null }
      AppRunScriptContract.FAILED_TASK -> failedTask = tokens.drop(2).joinToString(" ").ifBlank { null }
      AppRunScriptContract.CANDIDATES -> {
        val value =
          tokens.drop(2).joinToString(" ").let { raw ->
            if (raw.startsWith('<') && raw.endsWith('>')) raw.substring(1, raw.length - 1) else raw
          }
        candidates = value.split(',').map(String::trim).filter(String::isNotEmpty)
      }
      AppRunScriptContract.LAUNCHED -> launched = tokens.drop(2).joinToString(" ").ifBlank { null }
      AppRunScriptContract.FAILURE -> failure = tokens.drop(2).joinToString(" ").ifBlank { null }
      AppRunScriptContract.STEP ->
        when (tokens.getOrNull(2)) {
          AppRunScriptContract.STEP_BUILD ->
            listener.onProgress(
              AppRunProgress(
                AppRunStep.BUILDING,
                tokens.drop(STEP_VALUE_OFFSET).joinToString(" ").ifBlank { null },
              ),
            )
          AppRunScriptContract.STEP_LAUNCH -> listener.onProgress(AppRunProgress(AppRunStep.LAUNCHING))
        }
    }
  }

  private companion object {
    const val STEP_VALUE_OFFSET = 3
  }
}

package skillbill.engine.agentoutput

internal fun topLevelJsonObjectCandidates(text: String): List<String> {
  val candidates = mutableListOf<String>()
  var depth = 0
  var start = -1
  var inString = false
  var escaped = false
  text.forEachIndexed { index, char ->
    when {
      escaped -> escaped = false
      inString && char == '\\' -> escaped = true
      char == '"' -> inString = !inString
      inString -> Unit
      char == '{' -> {
        if (depth == 0) {
          start = index
        }
        depth += 1
      }
      char == '}' && depth > 0 -> {
        depth -= 1
        if (depth == 0 && start >= 0) {
          candidates += text.substring(start, index + 1)
          start = -1
        }
      }
    }
  }
  return candidates
}

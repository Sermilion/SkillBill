package skillbill.engine.agentoutput

import skillbill.application.agentoutput.headAndTailExcerpt

internal fun stderrExcerpt(
  stderr: String,
  maxChars: Int,
): String? = headAndTailExcerpt(stderr, maxChars)

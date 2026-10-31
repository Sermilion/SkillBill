package skillbill.engine.agentoutput

import skillbill.application.agentoutput.headAndTailExcerpt

public fun stderrExcerpt(
  stderr: String,
  maxChars: Int,
): String? = headAndTailExcerpt(stderr, maxChars)

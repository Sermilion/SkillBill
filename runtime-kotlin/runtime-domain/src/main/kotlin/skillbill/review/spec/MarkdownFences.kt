package skillbill.review.spec

/**
 * Tells which spec lines sit outside fenced code blocks, so heading-based section parsing does not
 * treat example text such as a fenced `## Unblock` as a section boundary.
 */
object MarkdownFences {
  private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})""")

  /**
   * Returns one flag per line: true when the line is outside every fence and is not a fence marker.
   * An unclosed fence would swallow the rest of the spec, so that case marks every line as outside.
   */
  fun outsideFenceMask(lines: List<String>): BooleanArray {
    val mask = BooleanArray(lines.size)
    var openFence: String? = null
    for ((index, line) in lines.withIndex()) {
      val marker = FENCE.find(line)?.groupValues?.get(1)
      mask[index] = openFence == null && marker == null
      if (marker != null) openFence = fenceAfter(openFence, marker)
    }
    return if (openFence == null) mask else BooleanArray(lines.size) { true }
  }

  private fun fenceAfter(
    openFence: String?,
    marker: String,
  ): String? =
    when {
      openFence == null -> marker
      marker[0] == openFence[0] && marker.length >= openFence.length -> null
      else -> openFence
    }
}

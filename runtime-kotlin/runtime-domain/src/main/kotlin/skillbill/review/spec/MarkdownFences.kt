package skillbill.review.spec

object MarkdownFences {
  private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})""")

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

package skillbill.engine.specbrowser

private val RUN_PATTERN = Regex("""\d+|\D+""")

internal val specNaturalOrder: Comparator<String> =
  Comparator<String> { left, right -> compareRuns(runsOf(left), runsOf(right)) }
    .thenComparing(naturalOrder<String>())

private fun runsOf(name: String): List<String> = RUN_PATTERN.findAll(name.lowercase()).map { it.value }.toList()

private fun compareRuns(
  left: List<String>,
  right: List<String>,
): Int {
  for ((leftRun, rightRun) in left.zip(right)) {
    val result =
      if (leftRun.first().isDigit() && rightRun.first().isDigit()) {
        compareDigitRuns(leftRun, rightRun)
      } else {
        leftRun.compareTo(rightRun)
      }
    if (result != 0) return result
  }
  return left.size.compareTo(right.size)
}

private fun compareDigitRuns(
  left: String,
  right: String,
): Int {
  val leftDigits = left.trimStart('0')
  val rightDigits = right.trimStart('0')
  return if (leftDigits.length != rightDigits.length) {
    leftDigits.length.compareTo(rightDigits.length)
  } else {
    leftDigits.compareTo(rightDigits)
  }
}

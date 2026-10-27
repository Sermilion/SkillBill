package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition

object AcceptanceCriterionEvidencePaths {
  fun sharedFilePairs(
    criteria: List<AcceptanceCriterionRepairCriterion>,
    isTestPath: (String) -> Boolean,
  ): List<AcceptanceCriterionSameFilePair> {
    val closed =
      criteria.filter {
        it.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED && it.closureEvidenceRef != null
      }.sortedBy { it.canonicalRef }
    val pathsByRef = closed.associate { it.canonicalRef to pathsOf(it, isTestPath) }
    return closed.flatMapIndexed { index, first ->
      closed.drop(index + 1).mapNotNull { second ->
        val shared = pathsByRef.getValue(first.canonicalRef).intersect(pathsByRef.getValue(second.canonicalRef))
        shared.takeIf { it.isNotEmpty() }
          ?.let { AcceptanceCriterionSameFilePair(first.canonicalRef, second.canonicalRef, it.sorted()) }
      }
    }
  }

  fun pathsOf(
    criterion: AcceptanceCriterionRepairCriterion,
    isTestPath: (String) -> Boolean,
  ): Set<String> {
    val evidence = criterion.closureEvidenceRef.orEmpty()
    val ref = Regex("""(?<![\w-])${Regex.escape(criterion.canonicalRef)}(?![\w-])""", RegexOption.IGNORE_CASE)
    val named = evidence.lineSequence().filter { line -> ref.containsMatchIn(line) }
    val lines = named.toList().ifEmpty { evidence.lines() }
    return lines.flatMap { line -> PATH_CITATION.findAll(line).map { normalize(it.groupValues[1]) }.toList() }
      .filterNot(isTestPath).toSet()
  }

  fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./")

  private val PATH_CITATION = Regex("""([\w.\-]+(?:/[\w.\-]+)*\.\w+):\d+""")
}

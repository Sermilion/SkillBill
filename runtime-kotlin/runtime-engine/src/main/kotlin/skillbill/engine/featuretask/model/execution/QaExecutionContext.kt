package skillbill.engine.featuretask.model.execution

import skillbill.model.FileLocation
import skillbill.ports.qa.model.QaWorkspace
import skillbill.scaffold.model.QaTargetKind
import java.nio.file.Path

data class QaExecutionContext(
  val workspace: QaWorkspace,
  val expected: QaExpectedIdentity,
  val target: QaExecutionTarget,
  val policy: QaPolicy = QaPolicy.STANDALONE,
  val preparationWarnings: List<String> = emptyList(),
  val callerBaseline: QaCallerBaseline? = null,
)

sealed interface QaExecutionTarget {
  data class Supported(
    val targets: List<QaDeclaredTarget>,
    val checkout: Path,
  ) : QaExecutionTarget {
    init {
      require(targets.isNotEmpty()) { "A supported QA target needs at least one declared target." }
    }
  }

  data class Skipped(val reason: QaSkipReason, val detail: String) : QaExecutionTarget
}

data class QaDeclaredTarget(
  val packSlug: String,
  val kinds: Set<QaTargetKind>,
  val guidancePath: FileLocation,
)

data class QaExpectedIdentity(
  val headRevision: String,
  val treeIdentity: String,
  val evidencePrefix: String,
)

data class QaCallerBaseline(
  val head: String,
  val treeIdentity: String,
  val status: String,
)

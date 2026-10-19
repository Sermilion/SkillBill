package skillbill.engine.featuretask.model.execution

import skillbill.model.FileLocation
import skillbill.ports.qa.model.QaWorkspace
import skillbill.scaffold.model.QaPlatform

data class QaExecutionContext(
  val workspace: QaWorkspace,
  val expected: QaExpectedIdentity,
  val target: QaExecutionTarget,
  val policy: QaPolicy = QaPolicy.STANDALONE,
)

sealed interface QaExecutionTarget {
  data class Supported(val guidancePath: FileLocation, val platforms: Set<QaPlatform>) : QaExecutionTarget

  data class Skipped(val reason: QaSkipReason, val detail: String) : QaExecutionTarget
}

data class QaExpectedIdentity(
  val headRevision: String,
  val treeIdentity: String,
  val evidencePrefix: String,
)

package skillbill.engine.featuretask.model.execution

import skillbill.model.FileLocation
import skillbill.ports.qa.model.QaWorkspace
import skillbill.scaffold.model.QaPlatform

data class QaExecutionContext(
  val workspace: QaWorkspace,
  val expected: QaExpectedIdentity,
  val guidancePath: FileLocation,
  val platforms: Set<QaPlatform>,
)

data class QaExpectedIdentity(
  val headRevision: String,
  val treeIdentity: String,
  val evidencePrefix: String,
)

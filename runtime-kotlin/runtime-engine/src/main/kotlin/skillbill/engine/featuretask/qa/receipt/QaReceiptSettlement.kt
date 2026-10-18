package skillbill.engine.featuretask.qa.receipt

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.error.featuretask.qa.qaReceiptMissing
import skillbill.ports.qa.QaWorkspacePort

@Inject
class QaReceiptSettlement(
  private val validator: QaReceiptValidator,
  private val workspaces: QaWorkspacePort,
) {
  fun settle(qa: QaExecutionContext): QaReceipt {
    val raw = workspaces.readReceipt(qa.workspace) ?: throw qaReceiptMissing(qa.workspace.receiptPath.toString())
    return validator.validate(raw, qa.expected)
  }
}

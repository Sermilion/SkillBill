package skillbill.application.approval.model

import skillbill.approval.model.ApprovalGrantRefusal
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.ports.approval.model.ApprovalDeliveryProgress

data class ApprovalOperatorView(
  val record: ApprovalRequestRecord,
  val providerDisposition: String,
  val delivery: ApprovalDeliveryProgress?,
  val oneTimeRefusal: ApprovalGrantRefusal?,
  val alwaysAllowRefusal: ApprovalGrantRefusal?,
  val ownerLive: Boolean,
  val patternRefusal: ApprovalGrantRefusal? = null,
  val suggestedPattern: String? = null,
  val rulePattern: String? = null,
)

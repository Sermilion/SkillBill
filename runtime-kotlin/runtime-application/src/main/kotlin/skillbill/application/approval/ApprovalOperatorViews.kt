package skillbill.application.approval

import skillbill.application.approval.model.ApprovalOperatorView
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.approval.model.ApprovalGrantRefusal
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.approval.model.durableRefusal
import skillbill.approval.model.onceRefusal
import skillbill.ports.approval.ApprovalStore
import skillbill.ports.approval.model.ApprovalStoredRequest
import java.time.Instant

internal fun ApprovalStoredRequest.toOperatorView(
  store: ApprovalStore,
  now: Instant,
): ApprovalOperatorView {
  val lease = store.owner(record.ownerKey())
  val mode = lease?.executionMode ?: ApprovalExecutionMode.READ_ONLY
  val allow = record.options.firstOrNull { it.kind == ONE_TIME_ALLOW_OPTION_KIND }
  return ApprovalOperatorView(
    record = record,
    providerDisposition = facts.providerDisposition.wireValue,
    delivery = delivery,
    oneTimeRefusal = if (allow == null) ApprovalGrantRefusal.NO_ONE_TIME_GRANT else facts.onceRefusal(mode, allow),
    alwaysAllowRefusal =
      if (allow == null) ApprovalGrantRefusal.NO_ONE_TIME_GRANT else facts.durableRefusal(mode, allow, record),
    ownerLive = lease?.isLive(now) == true,
  )
}

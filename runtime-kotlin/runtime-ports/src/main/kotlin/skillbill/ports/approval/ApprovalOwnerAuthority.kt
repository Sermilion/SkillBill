package skillbill.ports.approval

import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult

/**
 * The narrow, owner-side face of the approval broker. A process that spawns an agent opens one generation per spawn,
 * registers the permission requests that process raises, and releases the generation when the process ends. It cannot
 * decide a request, read another generation's records or hold more than the response capability it passed in.
 */
interface ApprovalOwnerAuthority {
  /**
   * Resolves the canonical repository identity of [request]'s checkout, mints a fresh process generation and registers
   * it with [effects] as the only response capability. Refused when the identity cannot be resolved or the
   * generation is already registered; the caller must not spawn then.
   */
  fun open(
    request: ApprovalOwnerOpenRequest,
    effects: ApprovalOwnerEffects,
  ): ApprovalOwnerOpenResult
}

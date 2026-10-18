package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.scaffold.model.QaPlatform

internal fun decodeQaReceipt(wire: Map<String, Any?>): QaReceipt {
  val identity = wire.objectAt(QaReceiptPayloadKeys.IDENTITY)
  return QaReceipt(
    verdict = wire.wireEnum(QaReceiptPayloadKeys.VERDICT, QaVerdict::fromWire),
    headRevision = identity.text(QaReceiptPayloadKeys.HEAD_REVISION),
    treeIdentity = identity.text(QaReceiptPayloadKeys.TREE_IDENTITY),
    hasArtifact = wire[QaReceiptPayloadKeys.ARTIFACT] != null,
    policy = wire.wireEnum(QaReceiptPayloadKeys.POLICY, QaPolicy::fromWire),
    target = JsonCodec.anyToStringAnyMap(wire[QaReceiptPayloadKeys.TARGET])?.let(::decodeTarget),
    flows = wire.mapList(QaReceiptPayloadKeys.FLOWS).map(::decodeFlow),
    assertions =
      wire.mapList(QaReceiptPayloadKeys.ASSERTIONS).map { assertion ->
        QaAssertion(
          flowId = assertion.text(QaReceiptPayloadKeys.FLOW_ID),
          outcome = assertion.wireEnum(QaReceiptPayloadKeys.OUTCOME, QaAssertionOutcome::fromWire),
        )
      },
    findings =
      wire.mapList(QaReceiptPayloadKeys.FINDINGS).map { finding ->
        QaFinding(
          id = finding.text(QaReceiptPayloadKeys.ID),
          evidencePaths =
            finding.mapList(QaReceiptPayloadKeys.EVIDENCE_REFS).map { it.text(QaReceiptPayloadKeys.PATH) },
        )
      },
    skipReason =
      wire[QaReceiptPayloadKeys.SKIP_REASON]?.let {
        wire.wireEnum(QaReceiptPayloadKeys.SKIP_REASON, QaSkipReason::fromWire)
      },
    incompleteReason =
      wire[QaReceiptPayloadKeys.INCOMPLETE_REASON]?.let {
        wire.wireEnum(QaReceiptPayloadKeys.INCOMPLETE_REASON, QaIncompleteReason::fromWire)
      },
    reasonDetail = wire[QaReceiptPayloadKeys.REASON_DETAIL] as? String,
    fallbacks =
      wire.mapList(QaReceiptPayloadKeys.FALLBACKS).map { fallback ->
        QaFallback(
          kind = fallback.wireEnum(QaReceiptPayloadKeys.KIND, QaFallbackKind::fromWire),
          reason = fallback.text(QaReceiptPayloadKeys.REASON),
        )
      },
    cleanup = decodeCleanup(wire.objectAt(QaReceiptPayloadKeys.CLEANUP)),
    wire = wire,
  )
}

private fun decodeTarget(target: Map<String, Any?>): QaTarget =
  QaTarget(
    platform = target.wireEnum(QaReceiptPayloadKeys.PLATFORM, QaPlatform::fromWire),
    deviceFingerprint = target.text(QaReceiptPayloadKeys.DEVICE_FINGERPRINT),
    kind = target.wireEnum(QaReceiptPayloadKeys.KIND, QaTargetKind::fromWire),
    selection = target.wireEnum(QaReceiptPayloadKeys.SELECTION, QaTargetSelection::fromWire),
  )

private fun decodeFlow(flow: Map<String, Any?>): QaFlow =
  QaFlow(
    id = flow.text(QaReceiptPayloadKeys.ID),
    name = flow.text(QaReceiptPayloadKeys.NAME),
    status = flow.wireEnum(QaReceiptPayloadKeys.STATUS, QaFlowStatus::fromWire),
    hardwareOnly = flow[QaReceiptPayloadKeys.HARDWARE_ONLY] == true,
    untestedReason = flow[QaReceiptPayloadKeys.UNTESTED_REASON] as? String,
  )

private fun decodeCleanup(cleanup: Map<String, Any?>): QaCleanup =
  QaCleanup(
    outcome = cleanup.wireEnum(QaReceiptPayloadKeys.OUTCOME, QaCleanupOutcome::fromWire),
    retainedResources =
      cleanup.mapList(QaReceiptPayloadKeys.RETAINED_RESOURCES).map { resource ->
        QaRetainedResource(
          kind = resource.text(QaReceiptPayloadKeys.KIND),
          identity = resource.text(QaReceiptPayloadKeys.IDENTITY),
        )
      },
    guidance = cleanup[QaReceiptPayloadKeys.GUIDANCE] as? String,
  )

private fun Map<String, Any?>.text(key: String): String =
  this[key] as? String ?: throw invalidQaReceipt("'$key' must be a string.")

private fun Map<String, Any?>.objectAt(key: String): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(this[key]) ?: throw invalidQaReceipt("'$key' must be an object.")

private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
  JsonCodec.anyToStringAnyMapList(this[key]).orEmpty()

private fun <T> Map<String, Any?>.wireEnum(
  key: String,
  fromWire: (String) -> T?,
): T = (this[key] as? String)?.let(fromWire) ?: throw invalidQaReceipt("'$key' has an unknown value.")

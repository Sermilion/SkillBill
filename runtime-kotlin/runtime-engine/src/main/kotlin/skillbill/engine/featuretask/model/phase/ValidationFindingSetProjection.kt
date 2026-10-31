package skillbill.engine.featuretask.model.phase

import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationGateFindingPayloadKeys
import skillbill.ports.validation.model.ValidationGateFinding

public data class ValidationFindingSetProjection(
  public val findings: List<ValidationGateFinding>,
) {
  public fun toHandoffMaps(): List<Map<String, String?>> =
    findings.map { finding ->
      linkedMapOf(
        ValidationGateFindingPayloadKeys.MODULE to finding.module,
        ValidationGateFindingPayloadKeys.RULE_OR_TEST_ID to finding.ruleOrTestId,
        ValidationGateFindingPayloadKeys.MESSAGE to finding.message,
        ValidationGateFindingPayloadKeys.LOCATION to finding.location,
        ValidationEvidencePayloadKeys.FAILURE_SUBJECT to finding.failureSubject.wireValue,
      )
    }
}

package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity

internal const val QA_TEST_HEAD = "1111111111111111111111111111111111111111"
internal const val QA_TEST_TREE = "2222222222222222222222222222222222222222222222222222222222222222"
internal const val QA_TEST_DIGEST = "3333333333333333333333333333333333333333333333333333333333333333"

internal fun qaTestExpected(invocationId: String = "phr-qa-test"): QaExpectedIdentity =
  QaExpectedIdentity(QA_TEST_HEAD, QA_TEST_TREE, ".git/skill-bill/qa/$invocationId/")

internal fun qaTestReceipt(
  verdict: QaVerdict,
  expected: QaExpectedIdentity = qaTestExpected(),
): Map<String, Any?> {
  val base =
    mapOf(
      "contract_version" to "0.1",
      "verdict" to verdict.wireValue,
      "identity" to mapOf("head_revision" to expected.headRevision, "tree_identity" to expected.treeIdentity),
      "artifact" to mapOf("kind" to "apk", "path_ref" to "app/build/outputs/apk/app.apk", "sha256" to QA_TEST_DIGEST),
      "policy" to "standalone",
      "target" to
        mapOf(
          "platform" to "android",
          "device_fingerprint" to QA_TEST_DIGEST,
          "kind" to "physical",
          "selection" to "single",
        ),
      "flows" to listOf(qaTestFlow()),
      "assertions" to listOf(qaTestAssertion("pass")),
      "findings" to emptyList<Any?>(),
      "cleanup" to mapOf("outcome" to "released", "retained_resources" to emptyList<Any?>()),
    )
  return when (verdict) {
    QaVerdict.PASSED -> base
    QaVerdict.FINDINGS_OPEN ->
      base + mapOf("assertions" to listOf(qaTestAssertion("fail")), "findings" to listOf(qaTestFinding(expected)))
    QaVerdict.SKIPPED ->
      base +
        mapOf(
          "artifact" to null,
          "target" to null,
          "flows" to emptyList<Any?>(),
          "assertions" to emptyList<Any?>(),
          "skip_reason" to "offline_target",
          "reason_detail" to "The only attached device is offline.",
        )
    QaVerdict.INCOMPLETE -> base + mapOf("incomplete_reason" to "device_lost")
  }
}

internal fun qaTestFlow(hardwareOnly: Boolean = false): Map<String, Any?> =
  mapOf("id" to "login", "name" to "Log in", "status" to "exercised", "hardware_only" to hardwareOnly)

internal fun qaTestJson(receipt: Map<String, Any?>): String = JsonCodec.mapToJsonString(receipt)

private fun qaTestAssertion(outcome: String): Map<String, Any?> =
  mapOf(
    "flow_id" to "login",
    "description" to "The home screen shows the signed-in user.",
    "outcome" to outcome,
    "criteria" to listOf("AC-1"),
  )

private fun qaTestFinding(expected: QaExpectedIdentity): Map<String, Any?> =
  mapOf(
    "id" to "F-1",
    "affected_criteria" to listOf("AC-1"),
    "steps" to listOf("Open the app", "Log in"),
    "expected" to "The home screen shows the user name.",
    "observed" to "The home screen stays blank.",
    "evidence_refs" to
      listOf(
        mapOf("kind" to "screenshot", "path" to "${expected.evidencePrefix}home.png", "sha256" to QA_TEST_DIGEST),
      ),
  )

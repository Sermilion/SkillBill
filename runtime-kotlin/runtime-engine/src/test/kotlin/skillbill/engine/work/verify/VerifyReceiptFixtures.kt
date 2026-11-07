package skillbill.engine.work.verify

import skillbill.contracts.JsonCodec

internal object VerifyReceiptFixtures {
  private val fixtures: Map<String, Any?> by lazy {
    val text = requireNotNull(javaClass.getResource("verify-run-receipts.json")).readText()
    requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(text)))
  }

  fun artifact(key: String): Map<String, Any?> = requireNotNull(JsonCodec.anyToStringAnyMap(fixtures[key]))

  fun field(
    key: String,
    field: String,
  ): String = artifact(key)[field] as String
}

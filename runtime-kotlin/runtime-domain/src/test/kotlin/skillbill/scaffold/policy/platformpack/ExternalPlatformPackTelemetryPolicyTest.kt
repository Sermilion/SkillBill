package skillbill.scaffold.policy.platformpack

import skillbill.contracts.config.ExternalPlatformPackTelemetryPayloadKeys
import skillbill.error.core.ambiguousExternalPlatformPack
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExternalPlatformPackTelemetryPolicyTest {
  @Test
  fun `remote payload keeps slug and source kind and drops path and guidance`() {
    val secretPath = "/home/author/private/company-packs/kotlin"
    val guidance = "Open the author README at $secretPath before retrying."
    val error =
      ambiguousExternalPlatformPack(
        "External platform pack slug 'kotlin' is declared by '$secretPath'. $guidance",
      )

    val payload =
      externalPlatformPackTelemetryPayload(
        error,
        slug = "kotlin",
        sourceKind = PlatformPackSourceKind.EXTERNAL,
      )

    assertEquals(
      "ExternalPlatformPackFailureCode.AMBIGUOUS",
      payload[ExternalPlatformPackTelemetryPayloadKeys.ERROR_TYPE],
    )
    assertEquals("kotlin", payload[ExternalPlatformPackTelemetryPayloadKeys.PLATFORM_SLUG])
    assertEquals("external", payload[ExternalPlatformPackTelemetryPayloadKeys.SOURCE_KIND])
    assertEquals(
      "ambiguous_external_platform_pack",
      payload[ExternalPlatformPackTelemetryPayloadKeys.FAILURE_FAMILY],
    )
    val serialized = payload.values.joinToString(" ")
    assertFalse(secretPath in serialized)
    assertFalse("README" in serialized)
    assertFalse(guidance in serialized)
  }
}

package skillbill.scaffold.policy.platformpack

import skillbill.contracts.config.ExternalPlatformPackTelemetryPayloadKeys
import skillbill.error.core.ExternalPlatformPackFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind

fun externalPlatformPackTelemetryPayload(
  error: Throwable,
  slug: String? = null,
  sourceKind: PlatformPackSourceKind? = null,
): Map<String, String> =
  buildMap {
    put(
      ExternalPlatformPackTelemetryPayloadKeys.ERROR_TYPE,
      error.failureCodeLabel() ?: error::class.simpleName.orEmpty(),
    )
    slug?.let { put(ExternalPlatformPackTelemetryPayloadKeys.PLATFORM_SLUG, it) }
    sourceKind?.let { put(ExternalPlatformPackTelemetryPayloadKeys.SOURCE_KIND, it.wireValue) }
    put(
      ExternalPlatformPackTelemetryPayloadKeys.FAILURE_FAMILY,
      when (error) {
        is SkillBillRuntimeException ->
          when (error.code) {
            ExternalPlatformPackFailureCode.AMBIGUOUS -> "ambiguous_external_platform_pack"
            ExternalPlatformPackFailureCode.CONFIG -> "external_platform_pack_config"
            ManifestFailureCode.INVALID_MANIFEST_SCHEMA -> "invalid_external_platform_pack_manifest"
            else -> "external_platform_pack"
          }
        else -> "external_platform_pack"
      },
    )
  }

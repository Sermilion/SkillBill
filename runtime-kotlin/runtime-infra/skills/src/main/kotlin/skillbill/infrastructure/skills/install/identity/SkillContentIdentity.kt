package skillbill.infrastructure.skills.install.identity

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.invalidSkillContentIdentityError
import skillbill.error.shellcontent.skillContentIdentityMismatchError
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.skills.scaffold.validation.shape.parseSkillFrontmatter
import skillbill.review.parsing.requireMatch

internal const val SKILL_CONTENT_IDENTITY_CONTRACT_VERSION = "0.1"
internal const val SKILL_CONTENT_IDENTITY_FILENAME = ".content-identity"

internal data class SkillContentIdentity(
  val canonicalSourceIdentity: String,
  val exactContentSha256: String,
  val normalizedMetadata: Map<String, String>,
) {
  init {
    require(canonicalSourceIdentity.isNotBlank()) { "canonicalSourceIdentity is required." }
    require(exactContentSha256.matches(SHA256_PATTERN)) { "exactContentSha256 must be a SHA-256 digest." }
    require(normalizedMetadata.keys.all { it.isNotBlank() }) { "normalizedMetadata keys must not be blank." }
  }

  fun compact(): String = JsonCodec.mapToJsonString(toMap())

  fun toMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to SKILL_CONTENT_IDENTITY_CONTRACT_VERSION,
      "canonical_source_identity" to canonicalSourceIdentity,
      "exact_content_sha256" to exactContentSha256,
      "normalized_metadata" to normalizedMetadata.toSortedMap(),
    )

  companion object {
    fun fromSource(sourceSkillDir: Path): SkillContentIdentity {
      val source = sourceSkillDir.toAbsolutePath().normalize()
      val contentFile = source.resolve("content.md")
      if (!Files.isRegularFile(contentFile)) {
        invalidIdentity(source.toString(), "content.md is missing")
      }
      val content = Files.readAllBytes(contentFile)
      val metadata =
        runCatching {
          parseSkillFrontmatter(content.toString(StandardCharsets.UTF_8))
        }.getOrElse { error ->
          invalidIdentity(
            source.toString(),
            "content.md frontmatter could not be normalized",
            error,
          )
        }
      if (metadata.isEmpty()) {
        invalidIdentity(source.toString(), "content.md frontmatter is missing")
      }
      val canonical = runCatching { source.toRealPath().toString() }.getOrElse { source.toString() }
      return SkillContentIdentity(canonical, sha256Hex(content), metadata.toSortedMap())
    }

    fun fromInstalled(stagingDir: Path): SkillContentIdentity {
      val marker = stagingDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME)
      if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
        invalidIdentity(stagingDir.toString(), "identity marker is missing")
      }
      val parsed =
        JsonCodec.parseObjectOrNull(Files.readString(marker, StandardCharsets.UTF_8))
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap)
          ?: invalidIdentity(stagingDir.toString(), "identity marker is not an object")
      val expectedFields =
        setOf(
          "contract_version",
          "canonical_source_identity",
          "exact_content_sha256",
          "normalized_metadata",
        )
      if (parsed.keys != expectedFields) {
        invalidIdentity(stagingDir.toString(), "identity marker fields are invalid")
      }
      if (parsed[SharedPayloadKeys.CONTRACT_VERSION] != SKILL_CONTENT_IDENTITY_CONTRACT_VERSION) {
        invalidIdentity(stagingDir.toString(), "identity marker contract version is invalid")
      }
      val source =
        parsed["canonical_source_identity"] as? String
          ?: invalidIdentity(stagingDir.toString(), "canonical source identity is missing")
      val digest =
        parsed["exact_content_sha256"] as? String
          ?: invalidIdentity(stagingDir.toString(), "exact content digest is missing")
      val metadata =
        (parsed["normalized_metadata"] as? Map<*, *>)
          ?.entries
          ?.associate { (key, value) ->
            val normalizedKey =
              key as? String
                ?: invalidIdentity(stagingDir.toString(), "metadata key is invalid")
            val normalizedValue =
              value as? String
                ?: invalidIdentity(stagingDir.toString(), "metadata value is invalid")
            normalizedKey to normalizedValue
          }
          ?: invalidIdentity(stagingDir.toString(), "normalized metadata is missing")
      return runCatching { SkillContentIdentity(source, digest, metadata.toSortedMap()) }
        .getOrElse { error ->
          invalidIdentity(stagingDir.toString(), error.message ?: "identity values are invalid", error)
        }
    }

    fun fromCompact(
      compact: String,
      sourceLabel: String,
    ): SkillContentIdentity {
      val parsed =
        JsonCodec.parseObjectOrNull(compact)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap)
          ?: invalidIdentity(sourceLabel, "compact identity is not an object")
      val expectedFields =
        setOf(
          "contract_version",
          "canonical_source_identity",
          "exact_content_sha256",
          "normalized_metadata",
        )
      if (parsed.keys != expectedFields) {
        invalidIdentity(sourceLabel, "compact identity fields are invalid")
      }
      if (parsed[SharedPayloadKeys.CONTRACT_VERSION] != SKILL_CONTENT_IDENTITY_CONTRACT_VERSION) {
        invalidIdentity(sourceLabel, "compact identity contract version is invalid")
      }
      val source =
        parsed["canonical_source_identity"] as? String
          ?: invalidIdentity(sourceLabel, "canonical source identity is missing")
      val digest =
        parsed["exact_content_sha256"] as? String
          ?: invalidIdentity(sourceLabel, "exact content digest is missing")
      val metadata =
        (parsed["normalized_metadata"] as? Map<*, *>)
          ?.entries
          ?.associate { (key, value) ->
            val normalizedKey = key as? String ?: invalidIdentity(sourceLabel, "metadata key is invalid")
            val normalizedValue = value as? String ?: invalidIdentity(sourceLabel, "metadata value is invalid")
            normalizedKey to normalizedValue
          }
          ?: invalidIdentity(sourceLabel, "normalized metadata is missing")
      return runCatching { SkillContentIdentity(source, digest, metadata.toSortedMap()) }
        .getOrElse { error ->
          invalidIdentity(sourceLabel, error.message ?: "identity values are invalid", error)
        }
    }

    fun requireMatch(
      supplied: SkillContentIdentity,
      installed: SkillContentIdentity,
    ) {
      if (supplied != installed) {
        throw skillContentIdentityMismatchError(supplied.compact(), installed.compact())
      }
    }

    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    private fun invalidIdentity(
      sourceLabel: String,
      reason: String,
      cause: Throwable? = null,
    ): Nothing = throw invalidSkillContentIdentityError(sourceLabel, reason, cause)
  }
}

internal fun suppliedSkillContentIdentity(sourceSkillDir: Path): SkillContentIdentity =
  SkillContentIdentity.fromSource(sourceSkillDir)

internal fun installedSkillContentIdentity(stagingDir: Path): SkillContentIdentity =
  SkillContentIdentity.fromInstalled(stagingDir)

internal fun requireMatchingSkillContentIdentity(
  supplied: SkillContentIdentity,
  installed: SkillContentIdentity,
) = SkillContentIdentity.requireMatch(supplied, installed)

internal fun routeInstalledSkillBody(
  suppliedCompactIdentity: String,
  installedStagingDir: Path,
) {
  val suppliedIdentity = SkillContentIdentity.fromCompact(suppliedCompactIdentity, "supplied")
  val installedIdentity = SkillContentIdentity.fromInstalled(installedStagingDir)
  SkillContentIdentity.requireMatch(suppliedIdentity, installedIdentity)
}

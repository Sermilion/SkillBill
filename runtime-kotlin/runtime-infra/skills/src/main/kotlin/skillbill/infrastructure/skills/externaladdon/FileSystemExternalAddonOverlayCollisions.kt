package skillbill.infrastructure.skills.externaladdon

import java.nio.file.Files
import java.nio.file.Path
import skillbill.error.core.ExternalAddonOverlayError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.PointerSpec

internal fun <T> wrapParserErrors(
  slug: String,
  block: () -> T,
): T =
  try {
    block()
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(
      error.isShellContentContractFailure() && error.code == ManifestFailureCode.INVALID_MANIFEST_SCHEMA,
    )
    throw ExternalAddonOverlayError(
      "External addon source for platform '$slug': fragment validation failed: ${error.message}",
      error,
    )
  }

internal fun verifySourceFile(
  sourcePath: Path,
  slug: String,
  filename: String,
) {
  val file = sourcePath.resolve(filename)
  if (!Files.isRegularFile(file)) {
    throw ExternalAddonOverlayError(
      "External addon source for platform '$slug': referenced addon file '$file' is missing.",
    )
  }
}

internal fun collisionMessage(
  slug: String,
  dir: String,
  name: String,
  existing: String,
  incoming: String,
): String =
  "External addon overlay for platform '$slug': pointer '$name' under '$dir' collides with an existing " +
    "pack-owned target '$existing' (external source declares '$incoming')."

internal fun targetCollisionMessage(
  slug: String,
  pointer: PointerSpec,
  outcome: PointerCollisionOutcome.TargetCollision,
): String =
  "External addon overlay for platform '$slug': pointer '${pointer.name}' under " +
    "'${pointer.skillRelativeDir}' writes target file '${pointer.target}' that collides with the " +
    "${outcome.origin} pointer '${outcome.existingName}' (silent overwrite refused)."

internal fun addonCollisionMessage(
  slug: String,
  dir: String,
  addonSlug: String,
  existing: GovernedAddonSelection,
  incoming: GovernedAddonSelection,
): String =
  "External addon overlay for platform '$slug': add-on slug '$addonSlug' under '$dir' collides with an " +
    "existing entry (existing entrypoint '${existing.entrypoint}', incoming entrypoint '${incoming.entrypoint}')."

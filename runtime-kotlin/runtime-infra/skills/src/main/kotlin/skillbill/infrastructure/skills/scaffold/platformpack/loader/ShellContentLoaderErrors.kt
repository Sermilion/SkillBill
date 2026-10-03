package skillbill.infrastructure.skills.scaffold.platformpack.loader

import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.error.shellcontent.invalidFallbackCapability as codedInvalidFallbackCapability
import skillbill.error.shellcontent.invalidManifestSchema as codedInvalidManifestSchema
import skillbill.error.shellcontent.invalidValidationGateDeclaration as codedInvalidValidationGateDeclaration
import skillbill.error.shellcontent.missingContentFile as codedMissingContentFile
import skillbill.error.shellcontent.missingRequiredSection as codedMissingRequiredSection

internal fun invalidManifestSchema(message: String): Nothing {
  throw codedInvalidManifestSchema(message)
}

internal fun missingManifestContent(message: String): Nothing {
  throw codedMissingContentFile(message)
}

internal fun missingManifestSection(message: String): Nothing {
  throw codedMissingRequiredSection(message)
}

internal fun invalidFallbackCapability(message: String): Nothing {
  throw codedInvalidFallbackCapability(message)
}

internal fun contractVersionMismatch(message: String): Nothing {
  throw ContractVersionMismatchError(message)
}

internal fun invalidValidationGateDeclaration(message: String): Nothing {
  throw codedInvalidValidationGateDeclaration(message)
}

internal fun invalidManifestSchemaFromPath(
  message: String,
  cause: Throwable,
): Nothing {
  throw codedInvalidManifestSchema(message, cause)
}

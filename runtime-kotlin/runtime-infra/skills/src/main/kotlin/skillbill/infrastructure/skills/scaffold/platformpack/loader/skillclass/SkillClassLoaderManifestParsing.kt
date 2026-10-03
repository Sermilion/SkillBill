package skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass

import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.scaffold.model.SkillClassSection

internal fun requireSkillClassManifestMap(
  classId: String,
  classFile: String,
  raw: Any?,
): Map<*, *> =
  raw as? Map<*, *> ?: throw invalidManifestSchema(
    "Skill class '$classId': manifest '$classFile' must be a YAML mapping at the top level.",
  )

internal fun validateDeclaredSkillClass(
  classId: String,
  declaredClass: String,
) {
  if (declaredClass != classId) {
    throw invalidManifestSchema(
      "Skill class '$classId': manifest 'class' field is '$declaredClass', expected '$classId' to match the filename.",
    )
  }
}

internal fun validateSkillClassContractVersion(
  classId: String,
  contractVersion: String,
) {
  if (contractVersion != SHELL_CONTRACT_VERSION) {
    throw ContractVersionMismatchError(
      "Skill class '$classId': declares contract_version '$contractVersion' " +
        "but the shell expects '$SHELL_CONTRACT_VERSION'.",
    )
  }
}

internal fun requireSkillClassMatcherList(
  classId: String,
  raw: Any?,
): List<*> = raw as? List<*> ?: throw invalidManifestSchema("Skill class '$classId': 'matchers' must be a list.")

internal fun parseSkillClassSection(
  classId: String,
  index: Int,
  entry: Any?,
): SkillClassSection {
  val mapping = requireSkillClassSectionMap(classId, index, entry)
  val heading = requireSkillClassSectionHeading(classId, index, mapping)
  val body = requireSkillClassSectionBody(classId, index, mapping)
  return SkillClassSection(heading = heading, body = body.trimEnd())
}

private fun requireSkillClassSectionMap(
  classId: String,
  index: Int,
  entry: Any?,
): Map<*, *> =
  entry as? Map<*, *> ?: throw invalidManifestSchema(
    "Skill class '$classId': sections[$index] must be a mapping with 'heading' and 'body'.",
  )

private fun requireSkillClassSectionHeading(
  classId: String,
  index: Int,
  mapping: Map<*, *>,
): String {
  val heading = (mapping["heading"] as? String)?.trim().orEmpty()
  if (heading.isEmpty()) {
    throw invalidManifestSchema("Skill class '$classId': sections[$index] is missing required 'heading'.")
  }
  return heading
}

private fun requireSkillClassSectionBody(
  classId: String,
  index: Int,
  mapping: Map<*, *>,
): String =
  mapping["body"] as? String
    ?: throw invalidManifestSchema("Skill class '$classId': sections[$index] is missing required 'body'.")

internal fun requireSkillClassStringEntry(
  classId: String,
  field: String,
  index: Int,
  entry: Any?,
): String =
  entry as? String
    ?: throw invalidManifestSchema("Skill class '$classId': '$field'[$index] must be a string.")

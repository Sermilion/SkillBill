
package skillbill.infrastructure.skills.scaffold.manifest

import skillbill.install.model.ListedSkillNames

private val README_CATALOG_ROW_PATTERN =
  Regex("""^\| `/(bill-[a-z0-9-]+|${ListedSkillNames.DISPATCHER})` \|[^\n]*$""", RegexOption.MULTILINE)

internal fun findReadmeCatalogRows(text: String): List<MatchResult> = README_CATALOG_ROW_PATTERN.findAll(text).toList()

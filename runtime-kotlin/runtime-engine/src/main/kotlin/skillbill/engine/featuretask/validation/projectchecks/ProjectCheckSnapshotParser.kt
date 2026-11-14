package skillbill.engine.featuretask.validation.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.scaffold.model.ValidationGateDeclaration

internal object ProjectCheckSnapshotParser {
  private val PARSER_INITIAL = listOf("<project-config-parser>")
  private val PARSER_VERIFY = listOf("<project-config-parser>", "full")

  fun parserDeclaration(snapshot: ProjectCheckCommandSnapshot): ValidationGateDeclaration? {
    val metadata = snapshot.parserMetadata ?: return null
    return ValidationGateDeclaration(
      fullGateCommand = PARSER_INITIAL,
      cacheBypassingFullGateCommand = PARSER_VERIFY,
      collectAllFullGateCommand = PARSER_INITIAL,
      cacheBypassingCollectAllFullGateCommand = PARSER_VERIFY,
      findings = metadata.findingsLocator(),
      suppressionMarkers = metadata.suppressionMarkers,
    )
  }
}

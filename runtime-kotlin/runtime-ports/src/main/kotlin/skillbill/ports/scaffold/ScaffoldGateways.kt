package skillbill.ports.scaffold

import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.catalog.model.ScaffoldShowResult
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult
import skillbill.ports.scaffold.source.model.ScaffoldFillResult
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Path

interface ScaffoldGateway {
  fun list(
    repoRoot: Path,
    skillNames: List<String>,
  ): ScaffoldListResult

  fun show(
    repoRoot: Path,
    skillName: String,
    contentMode: String,
  ): ScaffoldShowResult

  fun explain(
    repoRoot: Path,
    skillName: String?,
  ): ScaffoldExplainResult

  fun validate(
    repoRoot: Path,
    skillNames: List<String>,
  ): ScaffoldValidateResult

  fun upgrade(
    repoRoot: Path,
    skillNames: List<String>,
    validate: Boolean,
  ): ScaffoldUpgradeResult

  fun fill(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): ScaffoldFillResult

  fun saveExactContent(
    repoRoot: Path,
    skillName: String,
    content: String,
  ): ScaffoldSaveExactContentResult

  fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): ScaffoldEditWithBodyFileResult

  fun scaffold(
    request: ScaffoldCommandRequest,
    dryRun: Boolean,
  ): ScaffoldResult

  fun render(
    repoRoot: Path,
    skillName: String,
  ): ScaffoldRenderResult
}

interface ScaffoldCatalogGateway {
  fun approvedCodeReviewAreas(): Set<String>

  fun preShellFamilies(): Set<String>

  fun shelledFamilies(): Set<String>

  fun platformPackPresets(): Map<String, String>

  fun scaffoldPayloadVersion(): String

  fun discoverPilotedPlatformPacks(packsRoot: Path): List<PilotedPlatformPackProjection>

  fun discoverPlatformManifests(packsRoot: Path): List<PlatformManifest>

  fun discoverBaselineReviewCatalog(packsRoot: Path): BaselineReviewCatalog
}

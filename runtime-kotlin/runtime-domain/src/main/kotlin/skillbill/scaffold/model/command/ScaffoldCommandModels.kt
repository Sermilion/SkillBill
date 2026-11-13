package skillbill.scaffold.model.command

import skillbill.review.model.CodeReviewBaselineLayer

sealed class ScaffoldCommandRequest {
  abstract val scaffoldPayloadVersion: String

  abstract val repoRoot: String?

  data class HorizontalSkill(
    val name: String,
    val description: String = "",
    val contentBody: String? = null,
    val subagentSpecialists: List<String> = emptyList(),
    val suppressSubagents: Boolean = false,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  data class PlatformPack(
    val platform: String,
    val displayName: String = "",
    val description: String = "",
    val routingSignals: RoutingSignalsInput? = null,
    val baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
    val subagentSpecialists: List<String>? = null,
    val suppressSubagents: Boolean = false,
    val contentBody: String? = null,
    val nameOverride: String? = null,
    val packLocationPath: String? = null,
    val packRegistration: String? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  data class PlatformOverride(
    val platform: String,
    val family: String,
    val description: String = "",
    val contentBody: String? = null,
    val subagentSpecialists: List<String>? = null,
    val suppressSubagents: Boolean = false,
    val nameOverride: String? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  data class CodeReviewArea(
    val platform: String,
    val area: String,
    val description: String = "",
    val contentBody: String? = null,
    val nameOverride: String? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  data class AddOn(
    val name: String,
    val platform: String,
    val description: String = "",
    val body: String? = null,
    val addonLocationPath: String? = null,
    val consumerSkillDirs: List<String>? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  data class AgentAddon(
    val slug: String,
    val description: String,
    val agentIds: List<String>,
    val consumers: List<String>,
    val contentBody: String? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()
}

data class RoutingSignalsInput(
  val strong: List<String>? = null,
  val tieBreakers: List<String>? = null,
)

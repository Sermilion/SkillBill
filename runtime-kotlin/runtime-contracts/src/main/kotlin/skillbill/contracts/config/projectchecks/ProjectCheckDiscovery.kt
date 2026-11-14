package skillbill.contracts.config.projectchecks

const val PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION = "0.1"

object ProjectCheckDiscoveryKeys {
  const val CONTRACT_VERSION = ProjectCheckConfigKeys.CONTRACT_VERSION
  const val PROJECT_ID = ProjectCheckConfigKeys.PROJECT_ID
  const val CATEGORIES = "categories"
  const val BUILD = "build"
  const val LINT = "lint"
  const val TEST = "test"
  const val FORMATTING = "formatting"
  const val VALIDATION = "validation"
}

val ProjectCheckCategory.wireValue: String
  get() =
    when (this) {
      ProjectCheckCategory.BUILD -> ProjectCheckDiscoveryKeys.BUILD
      ProjectCheckCategory.LINT -> ProjectCheckDiscoveryKeys.LINT
      ProjectCheckCategory.TEST -> ProjectCheckDiscoveryKeys.TEST
      ProjectCheckCategory.FORMATTING -> ProjectCheckDiscoveryKeys.FORMATTING
      ProjectCheckCategory.VALIDATION -> ProjectCheckDiscoveryKeys.VALIDATION
    }

data class ProjectCheckDiscoveryResponse(
  val projectId: String,
  val categories: Map<ProjectCheckCategory, ProjectCheckCategoryConfig>,
)

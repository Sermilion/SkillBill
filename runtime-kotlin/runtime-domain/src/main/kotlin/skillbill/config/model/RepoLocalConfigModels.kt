package skillbill.config.model

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy

enum class SpecType(
  val id: String,
) {
  LOCAL("local"),
  LINEAR("linear"),
  ;

  companion object {
    val supportedIds: List<String> = entries.map(SpecType::id)
  }
}

enum class RepoLocalConfigKey(
  val key: String,
  val builtinDefault: String,
) {
  SPEC_TYPE("spec_type", SpecType.LOCAL.id),
  ;

  companion object {
    val knownKeys: Set<String> = entries.map(RepoLocalConfigKey::key).toSet()
  }
}

data class RepoLocalConfig(
  val specType: SpecType,
  val reviewContextBudget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  val validationGate: ValidationGateRepoConfig = ValidationGateRepoConfig.defaults(),
) {
  companion object {
    fun defaults(): RepoLocalConfig =
      RepoLocalConfig(
        specType = SpecType.LOCAL,
        reviewContextBudget = ReviewContextBudgetPolicy.DEFAULT,
        validationGate = ValidationGateRepoConfig.defaults(),
      )
  }
}

fun parseSpecType(raw: String?): SpecType? {
  val normalized = raw?.trim()?.lowercase() ?: return null
  return SpecType.entries.firstOrNull { specType -> specType.id == normalized }
}

object RepoLocalConfigResolution {
  fun <T> resolve(
    explicit: T?,
    config: T?,
    builtinDefault: T,
  ): T = explicit ?: config ?: builtinDefault
}

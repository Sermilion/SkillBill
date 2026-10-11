package skillbill.idestatus.model

enum class IdeStatusRepositoryIdentitySource {
  CONTROL,
  EXECUTION_IDENTITY,
  WORKFLOW_REGISTRATION,
  STANDALONE_REGISTRATION,
  GOAL_CHILDREN,
  ISSUE_UNIQUE,
}

object IdeStatusRepositoryIdentityPolicy {
  fun precedence(family: IdeStatusRepositoryIdentityFamily): List<IdeStatusRepositoryIdentitySource> =
    when (family) {
      IdeStatusRepositoryIdentityFamily.GOAL ->
        listOf(
          IdeStatusRepositoryIdentitySource.CONTROL,
          IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION,
          IdeStatusRepositoryIdentitySource.GOAL_CHILDREN,
        )
      IdeStatusRepositoryIdentityFamily.TASK ->
        listOf(
          IdeStatusRepositoryIdentitySource.EXECUTION_IDENTITY,
          IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION,
          IdeStatusRepositoryIdentitySource.STANDALONE_REGISTRATION,
        )
      IdeStatusRepositoryIdentityFamily.VERIFY ->
        listOf(
          IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION,
          IdeStatusRepositoryIdentitySource.ISSUE_UNIQUE,
        )
    }

  fun resolve(
    family: IdeStatusRepositoryIdentityFamily,
    evidence: Map<IdeStatusRepositoryIdentitySource, String?>,
  ): String? = precedence(family).firstNotNullOfOrNull(evidence::get)
}

enum class IdeStatusRepositoryIdentityFamily { GOAL, TASK, VERIFY }

package skillbill.ports.work.model

sealed interface IssueRepositoryIdentityLookup {
  data object Missing : IssueRepositoryIdentityLookup

  data class Unique(
    val repositoryIdentity: String,
  ) : IssueRepositoryIdentityLookup

  data class Ambiguous(
    val repositoryIdentities: List<String>,
  ) : IssueRepositoryIdentityLookup
}

package skillbill.ports.projectstore.model

data class RepositoryLineage(
  val rootCommits: List<String>,
  val remoteUrl: String?,
)

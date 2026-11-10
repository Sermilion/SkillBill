package skillbill.model

import java.nio.file.Path

data class EnvironmentContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = UnspecifiedEnvironment,
  val userHome: Path = UnspecifiedUserHome,
  val repositoryRoot: Path = UnspecifiedRepositoryRoot,
  val workingDirectory: Path = UnspecifiedWorkingDirectory,
) {
  companion object {
    val UnspecifiedEnvironment: Map<String, String> =
      object : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>> = emptySet()
      }
    val UnspecifiedUserHome: Path = Path.of(".skillbill-unspecified-user-home")
    val UnspecifiedRepositoryRoot: Path = Path.of(".skillbill-unspecified-repository-root")
    val UnspecifiedWorkingDirectory: Path = Path.of(".skillbill-unspecified-working-directory")
  }
}

package skillbill.infrastructure.host.process

import me.tatarka.inject.annotations.Inject
import skillbill.ports.blockincident.ProductIssueCredentialPort
import skillbill.ports.system.HostPlatformPort

@Inject
class EnvironmentGhProductIssueCredential(private val host: HostPlatformPort) : ProductIssueCredentialPort {
  override fun token(): String? =
    host.resolveEnvironment().let { environment ->
      environment["GH_TOKEN"]?.trim()?.takeIf(String::isNotBlank)
        ?: environment["GITHUB_TOKEN"]?.trim()?.takeIf(String::isNotBlank)
        ?: ghToken()
    }

  private fun ghToken(): String? {
    val result =
      try {
        BoundedExternalProcessRunner.run(
          BoundedExternalProcessRequest(
            argv = listOf("gh", "auth", "token"),
            mergeStderr = false,
            deadlineSeconds = 5,
            output = BoundedExternalProcessOutput.Captured(capBytes = TOKEN_OUTPUT_CAP.toLong()),
          ),
        )
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
      }
    if (result.launchFailure || result.timedOut) return null
    if (result.exitCode != 0 || result.readFailure != null) return null
    if (INSTALLER_OUTPUT_TRUNCATION_SENTINEL in result.output) return null
    return result.output.trim().takeIf { it.isNotEmpty() && it.length < TOKEN_OUTPUT_CAP }
  }

  private companion object {
    const val TOKEN_OUTPUT_CAP = 4096
  }
}

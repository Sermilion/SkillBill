package skillbill.infrastructure.host.projectchecks

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.host.jvm.resolveTelemetryStateDir
import skillbill.infrastructure.host.system.FileConfigDocumentLocks
import skillbill.model.EnvironmentContext
import skillbill.ports.config.projectchecks.ProjectCheckDiscoveryLock
import java.nio.file.Path

@Inject
class FileProjectCheckDiscoveryLock(
  environment: EnvironmentContext,
) : ProjectCheckDiscoveryLock {
  private val lockRoot: Path =
    resolveTelemetryStateDir(environment.environment, environment.userHome)
      .resolve(LOCK_DIRECTORY)

  override fun <T> withProjectOwnership(
    projectId: String,
    action: () -> T,
  ): T {
    val lockIdentity = lockRoot.resolve(projectId)
    return try {
      FileConfigDocumentLocks.withInterruptibleLocks(listOf(lockIdentity), action)
    } catch (failure: SkillBillRuntimeException) {
      if (failure.code != InstallFailureCode.REPO_LOCAL_CONFIG_FAILURE) {
        throw failure
      }
      throw SkillBillRuntimeException(
        ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID,
        "Project check discovery ownership for project '$projectId' at '$lockIdentity' failed: " +
          "${failure.message}. Retry discovery after the other owner releases the lock.",
        failure,
      )
    }
  }

  private companion object {
    const val LOCK_DIRECTORY = "project-check-discovery-locks"
  }
}

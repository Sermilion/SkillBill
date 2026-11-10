package skillbill.di.core

import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.http.JdkHttpRemoteTransport
import skillbill.model.EnvironmentContext
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Path

internal object RuntimeBootstrapBindings {
  fun processTempRoot(): Path = JdkHostPlatformPort.resolveTemporaryDirectory()

  fun runtimeContext(inputRuntimeContext: RuntimeContext): RuntimeContext {
    val inputEnvironment = inputRuntimeContext.environment
    val resolvedEnvironment =
      if (inputEnvironment.userHome == EnvironmentContext.UnspecifiedUserHome) {
        inputEnvironment.copy(userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
      } else {
        inputEnvironment
      }
    val environmentWithEnv =
      if (resolvedEnvironment.environment === EnvironmentContext.UnspecifiedEnvironment) {
        resolvedEnvironment.copy(environment = System.getenv())
      } else {
        resolvedEnvironment
      }
    val hostPlatform = inputRuntimeContext.callbacks.hostPlatformPort ?: processHostPlatform()
    val environmentWithWorkingDirectory =
      if (environmentWithEnv.workingDirectory == EnvironmentContext.UnspecifiedWorkingDirectory) {
        environmentWithEnv.copy(
          workingDirectory = hostPlatform.resolveWorkingDirectory().toAbsolutePath().normalize(),
        )
      } else {
        environmentWithEnv
      }
    val inputTransport = inputRuntimeContext.transport
    val resolvedTransport =
      inputTransport.copy(
        requester =
          inputTransport.requester
            ?: JdkHttpRemoteTransport.create(inputTransport.connectTimeout, inputTransport.requestTimeout),
      )
    val resolvedRepositoryRoot =
      if (environmentWithWorkingDirectory.repositoryRoot == EnvironmentContext.UnspecifiedRepositoryRoot) {
        environmentWithWorkingDirectory.copy(
          repositoryRoot =
            CanonicalRepositoryRoot.enclosingRepositoryRoot(environmentWithWorkingDirectory.workingDirectory),
        )
      } else {
        environmentWithWorkingDirectory.copy(
          repositoryRoot =
            CanonicalRepositoryRoot.enclosingRepositoryRoot(environmentWithWorkingDirectory.repositoryRoot),
        )
      }
    return inputRuntimeContext.copy(
      environment = resolvedRepositoryRoot,
      transport = resolvedTransport,
      callbacks = inputRuntimeContext.callbacks.copy(hostPlatformPort = hostPlatform),
    )
  }

  private fun processHostPlatform(): HostPlatformPort {
    val userName = System.getProperty("user.name")?.takeIf(String::isNotBlank)
    return object : HostPlatformPort by JdkHostPlatformPort {
      override fun resolveUserName(): String? = userName
    }
  }
}

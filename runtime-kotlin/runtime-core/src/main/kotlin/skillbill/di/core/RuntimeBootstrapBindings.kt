package skillbill.di.core

import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.http.JdkHttpRemoteTransport
import skillbill.model.EnvironmentContext
import java.nio.file.Path

internal object RuntimeBootstrapBindings {
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
    val inputTransport = inputRuntimeContext.transport
    val resolvedTransport =
      inputTransport.copy(
        requester =
          inputTransport.requester
            ?: JdkHttpRemoteTransport.create(inputTransport.connectTimeout, inputTransport.requestTimeout),
      )
    val resolvedRepositoryRoot =
      if (environmentWithEnv.repositoryRoot == EnvironmentContext.UnspecifiedRepositoryRoot) {
        environmentWithEnv.copy(repositoryRoot = CanonicalRepositoryRoot.enclosingRepositoryRoot(Path.of("")))
      } else {
        environmentWithEnv.copy(
          repositoryRoot = CanonicalRepositoryRoot.enclosingRepositoryRoot(environmentWithEnv.repositoryRoot),
        )
      }
    return inputRuntimeContext.copy(
      environment = resolvedRepositoryRoot,
      transport = resolvedTransport,
    )
  }
}

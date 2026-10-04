package skillbill.infrastructure.host.jvm

import skillbill.ports.system.HostPlatformPort
import java.nio.file.Path

object JdkHostPlatformPort : HostPlatformPort {
  override val osName: String get() = System.getProperty("os.name").orEmpty()
  override val jvmClassPath: String get() = System.getProperty("java.class.path").orEmpty()
  override val javaCommand: String? get() = ProcessHandle.current().info().command().orElse(null)
  override val pathSeparator: String get() = System.getProperty("path.separator", ":")

  override fun resolveUserHome(): Path = Path.of(System.getProperty("user.home"))

  override fun resolveEnvironment(): Map<String, String> = System.getenv()

  override fun resolveJavaHome(): Path = Path.of(System.getProperty("java.home"))

  override fun resolveWorkingDirectory(): Path = Path.of(System.getProperty("user.dir"))

  override fun resolveTemporaryDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir"))
}

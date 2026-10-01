package skillbill.ports.system

import java.nio.file.Path

interface HostPlatformPort {
  val osName: String
  val jvmClassPath: String
  val javaCommand: String?
  val pathSeparator: String

  fun resolveUserHome(): Path

  fun resolveEnvironment(): Map<String, String>

  fun resolveJavaHome(): Path

  fun resolveWorkingDirectory(): Path

  fun resolveTemporaryDirectory(): Path
}

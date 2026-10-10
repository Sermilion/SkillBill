package skillbill.infrastructure.host.jvm

import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateJvmResolverTest {
  @Test
  fun `a JDK inside the runtime image root is dropped from JAVA_HOME and from PATH before the guard decides`() {
    val imageRoot = Files.createTempDirectory("gate-jvm-image")
    val outsideImage = Files.createTempDirectory("gate-jvm-host")
    try {
      val leaked = writeJdkShapedHome(Files.createDirectories(imageRoot.resolve("jdk")))
      val explicit = writeJdkShapedHome(outsideImage)
      val environment =
        mutableMapOf(
          GateJvmEnvironmentKeys.JAVA_HOME to leaked.toString(),
          GateJvmEnvironmentKeys.SKILL_BILL_JAVA_HOME to explicit.toString(),
          GateJvmEnvironmentKeys.PATH to
            listOf(
              leaked.resolve("bin").toString(),
              explicit.resolve("bin").toString(),
            ).joinToString(File.pathSeparator),
        )

      dropRuntimeImageJava(environment, imageRoot.toRealPath())

      assertNull(environment[GateJvmEnvironmentKeys.JAVA_HOME])
      assertEquals(explicit.toString(), environment[GateJvmEnvironmentKeys.SKILL_BILL_JAVA_HOME])
      assertEquals(explicit.resolve("bin").toString(), environment[GateJvmEnvironmentKeys.PATH])
    } finally {
      imageRoot.toFile().deleteRecursively()
      outsideImage.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a runtime already running on a full JDK keeps the operator's own selection out of the drop list`() {
    val runningHome = Path.of(System.getProperty("java.home"))
    val environment =
      mutableMapOf(
        GateJvmEnvironmentKeys.SKILL_BILL_JAVA_HOME to runningHome.toString(),
        GateJvmEnvironmentKeys.JAVA_HOME to runningHome.toString(),
        GateJvmEnvironmentKeys.PATH to runningHome.resolve("bin").toString(),
      )

    val dropped = dropRuntimeImageJava(environment, runtimeImageRoot(JdkHostPlatformPort))

    assertEquals(emptyList(), dropped)
    assertEquals(runningHome.toString(), environment[GateJvmEnvironmentKeys.SKILL_BILL_JAVA_HOME])
    assertEquals(runningHome.resolve("bin").toString(), environment[GateJvmEnvironmentKeys.PATH])
  }

  @Test
  fun `SKILL_BILL_JAVA_HOME selects the gate JVM only while the guard accepts it`() {
    val accepted = Files.createTempDirectory("gate-jvm-explicit")
    val inherited = Files.createTempDirectory("gate-jvm-inherited")
    val rejected = Files.createTempDirectory("gate-jvm-rejected")
    try {
      writeJdkShapedHome(accepted)
      writeJdkShapedHome(inherited)
      val resolver = testGateJvmResolver()

      assertEquals(
        GateJvmDisposition.Export(accepted.toString()),
        resolver.resolve(guardInput(accepted, inherited)),
      )
      assertEquals(
        GateJvmDisposition.Export(inherited.toString()),
        resolver.resolve(guardInput(rejected, inherited)),
      )
    } finally {
      listOf(accepted, inherited, rejected).forEach { it.toFile().deleteRecursively() }
    }
  }

  @Test
  fun `a JDK that appears after a first resolution is used by the next resolution`() {
    val candidate = Files.createTempDirectory("gate-jvm-late-install")
    try {
      val resolver = testGateJvmResolver()
      val environment =
        mutableMapOf(
          GateJvmEnvironmentKeys.SKILL_BILL_JAVA_HOME to candidate.toString(),
          GateJvmEnvironmentKeys.PATH to hostPath(),
        )
      val beforeInstall = resolver.resolve(environment)
      writeJdkShapedHome(candidate)

      assertNotEquals(GateJvmDisposition.Export(candidate.toString()), beforeInstall)
      assertEquals(GateJvmDisposition.Export(candidate.toString()), resolver.resolve(environment))
    } finally {
      candidate.toFile().deleteRecursively()
    }
  }

  @Test
  fun `an empty PATH still evaluates the guard through an absolute POSIX sh`() {
    testGateJvmResolver().resolve(mutableMapOf(GateJvmEnvironmentKeys.PATH to ""))
  }

  @Test
  fun `a PATH that only names the runtime image still evaluates the guard`() {
    val imageRoot = Files.createTempDirectory("gate-jvm-image-path")
    try {
      val leaked = writeJdkShapedHome(Files.createDirectories(imageRoot.resolve("jdk")))
      val environment =
        mutableMapOf(
          GateJvmEnvironmentKeys.JAVA_HOME to leaked.toString(),
          GateJvmEnvironmentKeys.PATH to leaked.resolve("bin").toString(),
        )
      dropRuntimeImageJava(environment, imageRoot.toRealPath())

      testGateJvmResolver().resolve(environment)
    } finally {
      imageRoot.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a routine gate JVM resolution is recorded at info not warning`() {
    val accepted = Files.createTempDirectory("gate-jvm-info")
    val other = Files.createTempDirectory("gate-jvm-info-other")
    try {
      writeJdkShapedHome(accepted)
      writeJdkShapedHome(other)
      val recording = RecordingDiagnostics()
      GateJvmResolver(recording, JdkHostPlatformPort).resolve(guardInput(accepted, other))
      assertEquals(1, recording.infos.size)
      assertTrue(recording.infos.single().contains("branch=skill_bill_java_home"))
      assertEquals(emptyList(), recording.warnings)
    } finally {
      listOf(accepted, other).forEach { it.toFile().deleteRecursively() }
    }
  }

  @Test
  fun `an unresolved gate JVM resolution is recorded at warning`() {
    val recording = RecordingDiagnostics()
    GateJvmResolver(recording, JdkHostPlatformPort).recordDecision(
      mutableMapOf(GateJvmEnvironmentKeys.PATH to hostPath()),
      emptyList(),
      null,
      GateJvmDisposition.Unresolved("/opt/skill-bill/runtime", "21"),
    )
    assertEquals(1, recording.warnings.size)
    assertTrue(recording.warnings.single().contains("branch=unresolved"))
    assertEquals(emptyList(), recording.infos)
  }

  @Test
  fun `an unresolved gate JVM clears JAVA_HOME at the launch surface instead of failing the launch`() {
    val environment =
      mutableMapOf(
        GateJvmEnvironmentKeys.JAVA_HOME to "/opt/skill-bill/runtime",
        GateJvmEnvironmentKeys.PATH to hostPath(),
      )

    GateJvmDisposition.Unresolved(rejectedCandidate = "/opt/skill-bill/runtime", requiredMajor = "21")
      .applyTo(environment)

    assertNull(environment[GateJvmEnvironmentKeys.JAVA_HOME])
    assertEquals(hostPath(), environment[GateJvmEnvironmentKeys.PATH])
  }

  private fun guardInput(
    skillBillJavaHome: Path,
    javaHome: Path,
  ): MutableMap<String, String> =
    mutableMapOf(
      GateJvmEnvironmentKeys.SKILL_BILL_JAVA_HOME to skillBillJavaHome.toString(),
      GateJvmEnvironmentKeys.JAVA_HOME to javaHome.toString(),
      GateJvmEnvironmentKeys.PATH to hostPath(),
    )
}

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()
  val infos = mutableListOf<String>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun info(message: String) {
    infos += message
  }
}

package skillbill.cli.qa

import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import skillbill.ports.qa.model.QaResourceRegistration
import skillbill.ports.qa.model.QaResourceReleaseOutcome
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class QaResourceCommandTest {
  @Test
  fun `register prints its status and exits 0 for registered and conflict and 1 for rejected`() {
    val cases =
      listOf(
        QaResourceRegistration.Registered to Triple("registered", 0, null),
        QaResourceRegistration.Conflict("qa-other") to Triple("conflict", 0, "qa-other"),
        QaResourceRegistration.Rejected("not valid") to Triple("rejected", 1, null),
      )

    cases.forEach { (registration, expected) ->
      val state = run("register", FakePort(registration))

      val result = checkNotNull(state.result)
      assertEquals(expected.first, result.payload?.get("status"))
      assertEquals(expected.second, result.exitCode)
      assertEquals(expected.third, result.payload?.get("owner_invocation_id"))
      assertEquals("volume", result.payload?.get("kind"))
      assertEquals("sbqa-qa-run-data", result.payload?.get("identity"))
    }
  }

  @Test
  fun `release exits 1 only when the resource is retained`() {
    val resource = QaOwnedResource(QaOwnedResourceKind.VOLUME, "sbqa-qa-run-data")
    val cases =
      listOf(
        QaResourceReleaseOutcome.Released(resource) to ("released" to 0),
        QaResourceReleaseOutcome.NotHeld(resource) to ("not_held" to 0),
        QaResourceReleaseOutcome.Failed(resource, "docker is not available") to ("retained" to 1),
      )

    cases.forEach { (outcome, expected) ->
      val result = checkNotNull(run("release", FakePort(release = outcome)).result)

      assertEquals(expected.first, result.payload?.get("status"))
      assertEquals(expected.second, result.exitCode)
    }
  }

  private fun run(
    action: String,
    port: QaOwnedResourcePort,
  ): CliRunState {
    val state = CliRunState(stdinText = null)
    val inputs =
      CliRunInputs(
        databasePath = null,
        environment = emptyMap(),
        userHome = Path.of("/tmp"),
        repositoryRoot = Path.of("/tmp/repo"),
        liveStdout = {},
        liveStderr = {},
      )
    CommandLineParser.parseAndRun(
      QaResourceCommand(port, state, inputs),
      listOf(action, "--invocation", "qa-run", "--kind", "volume", "--identity", "sbqa-qa-run-data"),
    ) { parsed -> parsed.run() }
    return state
  }

  private class FakePort(
    private val registration: QaResourceRegistration = QaResourceRegistration.Registered,
    private val release: QaResourceReleaseOutcome? = null,
  ) : QaOwnedResourcePort {
    override fun register(
      repoRoot: Path,
      invocationId: String,
      resource: QaOwnedResource,
    ): QaResourceRegistration = registration

    override fun release(
      repoRoot: Path,
      invocationId: String,
      resource: QaOwnedResource,
    ): QaResourceReleaseOutcome = checkNotNull(release)

    override fun releaseAllFor(
      repoRoot: Path,
      invocationId: String,
    ): List<QaResourceReleaseOutcome> = emptyList()

    override fun reclaimAbandoned(repoRoot: Path): List<QaResourceReleaseOutcome> = emptyList()
  }
}

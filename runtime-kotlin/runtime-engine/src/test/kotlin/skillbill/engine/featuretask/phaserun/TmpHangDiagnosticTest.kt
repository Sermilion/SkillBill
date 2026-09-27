package skillbill.engine.featuretask.phaserun

import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.telemetryRunnerHarness
import kotlin.test.Test

class TmpHangDiagnosticTest {
  @Test
  fun durableDefaultHarnessRun() {
    val durable = telemetryRunnerHarness(RuntimeHarnessConfig())
    val worker = Thread { println("REPORT: " + durable.runner.run(durable.request)) }
    worker.isDaemon = true
    worker.start()
    worker.join(30_000)
    if (worker.isAlive) {
      println("HUNG STACK:\n" + worker.stackTrace.joinToString("\n") { "  at $it" })
      Thread.sleep(3_000)
      println("HUNG STACK 2:\n" + worker.stackTrace.take(40).joinToString("\n") { "  at $it" })
      error("durable run hung")
    }
  }
}

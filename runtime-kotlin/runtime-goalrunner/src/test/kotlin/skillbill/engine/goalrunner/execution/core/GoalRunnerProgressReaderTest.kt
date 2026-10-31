package skillbill.engine.goalrunner.execution.core

import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.support.GoalRunnerChildProgressRead
import kotlin.test.Test
import kotlin.test.assertIs

class GoalRunnerProgressReaderTest {
  @Test
  fun `throwing progress store is distinguishable from absent progress`() {
    val outcomes = RecordingOutcomeStore().apply { throwOnProgress = true }
    val reader = GoalRunnerProgressReader(outcomes)

    assertIs<GoalRunnerChildProgressRead.Failed>(reader.read("wfl-child"))
  }
}

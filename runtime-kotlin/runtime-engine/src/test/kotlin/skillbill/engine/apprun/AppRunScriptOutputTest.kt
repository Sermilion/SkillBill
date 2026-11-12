package skillbill.engine.apprun

import skillbill.engine.apprun.model.AppRunListener
import skillbill.engine.apprun.model.AppRunProgress
import skillbill.engine.apprun.model.AppRunScriptOutput
import skillbill.engine.apprun.model.AppRunStep
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRunScriptOutputTest {
  @Test
  fun `script markers preserve output and expose task progress`() {
    val lines = mutableListOf<String>()
    val progressEvents = mutableListOf<AppRunProgress>()
    val parser =
      AppRunScriptOutput(
        object : AppRunListener {
          override fun onProgress(progress: AppRunProgress) {
            progressEvents += progress
          }

          override fun onOutput(line: String) {
            lines += line
          }
        },
      )

    parser.accept("##app-run step build :app:installDebug")
    parser.accept("##app-run step launch")
    parser.accept("##app-run launched com.example/.MainActivity")

    assertEquals(
      listOf(AppRunProgress(AppRunStep.BUILDING, ":app:installDebug"), AppRunProgress(AppRunStep.LAUNCHING)),
      progressEvents,
    )
    assertEquals("com.example/.MainActivity", parser.launched)
    assertEquals(
      listOf(
        "##app-run step build :app:installDebug",
        "##app-run step launch",
        "##app-run launched com.example/.MainActivity",
      ),
      lines,
    )
  }
}

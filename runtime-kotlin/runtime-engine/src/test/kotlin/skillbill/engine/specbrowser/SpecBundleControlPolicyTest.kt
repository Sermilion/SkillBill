package skillbill.engine.specbrowser

import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBundleRunState
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.idestatus.model.IdeStatusLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecBundleControlPolicyTest {
  private data class Verdict(
    val runState: SpecBundleRunState,
    val canStart: Boolean,
    val startLabel: String,
    val canPause: Boolean,
    val canCancel: Boolean,
    val purgeVisible: Boolean,
    val canPurge: Boolean,
  )

  @Test
  fun `every run state offers only its own actions and purge is never offered while running`() {
    val cases =
      listOf(
        facts(null) to Verdict(SpecBundleRunState.NOT_STARTED, true, "Start", false, false, true, true),
        facts(live(IdeStatusLifecycleState.ACTIVE, false, options(canCancel = true, canLaunch = false))) to
          Verdict(SpecBundleRunState.RUNNING, false, "Resume", true, true, true, false),
        facts(live(IdeStatusLifecycleState.ACTIVE, true, options(canCancel = true, canLaunch = false))) to
          Verdict(SpecBundleRunState.PAUSE_REQUESTED, false, "Resume", false, true, true, false),
        facts(live(IdeStatusLifecycleState.CANCELED, false, options(false, canLaunch = true, label = "Run again"))) to
          Verdict(SpecBundleRunState.CANCELED, true, "Run again", false, false, true, true),
        facts(live(IdeStatusLifecycleState.TERMINAL, false, options(canCancel = false, canLaunch = false))) to
          Verdict(SpecBundleRunState.COMPLETE, false, "Resume", false, false, true, true),
      )

    cases.forEach { (input, expected) ->
      val control = specBundleControl(input)
      val actual =
        Verdict(
          control.runState,
          control.canStart,
          control.startLabel,
          control.canPause,
          control.canCancel,
          control.purgeVisible,
          control.canPurge,
        )
      assertEquals(expected, actual)
    }
  }

  private fun facts(live: ControlLive?) = SpecBundleControlFacts("SKILL-1", null, true, SpecBrowserStatus.PENDING, live)

  private fun live(
    lifecycle: IdeStatusLifecycleState,
    pauseRequested: Boolean,
    options: WorkRunOptions,
  ) = ControlLive("goal-1", "repo", lifecycle, pauseRequested, pauseEligible = true, options = options)

  private fun options(
    canCancel: Boolean,
    canLaunch: Boolean,
    label: String = "Resume",
  ) = WorkRunOptions(canCancel, canLaunch, label, null, listOf("codex"))
}

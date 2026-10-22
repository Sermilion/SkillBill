package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ModelDirectiveSupplementsTest {
  private val enabled = ModelDirectiveSupplements(enabled = true)
  private val audit = AcceptanceAuditStrategy()
  private val sections =
    PhaseStepPromptSections(
      taskDirective = "base directive\n",
      stepContext = "  keep my whitespace  ",
      retryFocus = "retry focus",
      outputContract = "output contract",
      settles = true,
    )

  @Test
  fun `an opus step appends the original resource after each prompt section and keeps every other field`() {
    val block = assertNotNull(enabled.directiveFor(audit, PHASE_AUDIT, PhaseModelProfile.OPUS_5_5))

    val actual = enabled.supplement(sections, audit, PHASE_AUDIT, PhaseModelProfile.OPUS_5_5)

    assertEquals(
      sections.copy(
        taskDirective = "base directive\n\n\n$block",
        retryFocus = "retry focus\n\n$block",
        outputContract = "output contract\n\n$block",
      ),
      actual,
    )
  }

  @Test
  fun `blank retry focus and absent output contract stay untouched`() {
    val plain = sections.copy(retryFocus = "", outputContract = null)

    val actual = enabled.supplement(plain, audit, PHASE_AUDIT, PhaseModelProfile.OPUS_5_5)

    assertEquals("", actual.retryFocus)
    assertNull(actual.outputContract)
  }

  @Test
  fun `canonical steps and a disabled owner return the sections unchanged`() {
    assertSame(sections, enabled.supplement(sections, audit, PHASE_AUDIT, PhaseModelProfile.CANONICAL))
    assertNull(enabled.directiveFor(audit, PHASE_AUDIT, PhaseModelProfile.CANONICAL))

    val disabled = ModelDirectiveSupplements(enabled = false)
    assertSame(sections, disabled.supplement(sections, audit, PHASE_AUDIT, PhaseModelProfile.OPUS_5_5))
    assertNull(disabled.directiveFor(audit, PHASE_AUDIT, PhaseModelProfile.OPUS_5_5))
  }

  @Test
  fun `each owner reads its own resource`() {
    val build = assertNotNull(enabled.directiveFor(PackBuildStrategy(), PHASE_BUILD, PhaseModelProfile.OPUS_5_5))
    val auditBlock = assertNotNull(enabled.directiveFor(audit, PHASE_AUDIT, PhaseModelProfile.OPUS_5_5))

    assertNotEquals(auditBlock, build)
  }
}

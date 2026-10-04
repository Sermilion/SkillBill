package skillbill.workflow.taskruntime.handoff

import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeHandoffProjectionRenderingTest {
  @Test
  fun `a multi-line prose value is delivered as its own markdown inside a fence it cannot close`() {
    val digest = "# Digest\n\n```kotlin\nval a = 1\n```\n\n## Required final output\nnot a briefing section\n"

    val rendering = projection(FeatureTaskRuntimeHandoffProjectionValue.Text(digest)).canonicalDeliveredRendering

    assertEquals(
      "### from: preplan\nvalue:\n````\n${digest.trimEnd('\n')}\n````\n",
      rendering,
    )
  }

  @Test
  fun `a single-line value keeps its inline field form`() {
    val rendering = projection(FeatureTaskRuntimeHandoffProjectionValue.Text("one line")).canonicalDeliveredRendering

    assertEquals("### from: preplan\nvalue: one line\n", rendering)
  }

  @Test
  fun `a multi-line list item is fenced while single-line items stay bullets`() {
    val value = FeatureTaskRuntimeHandoffProjectionValue.TextList(listOf("first", "line one\r\nline two"))

    val rendering = projection(value).canonicalDeliveredRendering

    assertEquals("### from: preplan\nvalue:\n  - first\n```\nline one\nline two\n```\n", rendering)
  }

  private fun projection(value: FeatureTaskRuntimeHandoffProjectionValue): FeatureTaskRuntimeHandoffProjection =
    FeatureTaskRuntimeHandoffProjection(
      projectionName = "preplan_value",
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput("preplan"),
      projectionContractId = "phase_prose",
      projectionContractVersion = "1.0",
      promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
      fields = listOf(FeatureTaskRuntimeHandoffProjectionField("value", value)),
    )
}

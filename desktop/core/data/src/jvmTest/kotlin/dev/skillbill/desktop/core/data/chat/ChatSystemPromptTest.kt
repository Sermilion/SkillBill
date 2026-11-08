package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSystemPromptTest {
  private val sharedStartWires = NewTaskActionKind.entries.map { it.wire }

  // An agent told to act on "this execution" hunts for one.
  @Test
  fun `the new-task prompt names the checkout and action rules without any execution guidance`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", "develop"))

    assertTrue("/work/app" in prompt)
    assertTrue("Base branch: develop" in prompt)
    assertTrue("skillbill-actions" in prompt)
    assertTrue("\"mode\":\"act\",\"start\":\"goal\"" in prompt)
    assertTrue("\"start\":\"goal\",\"title\":\"" in prompt)
    sharedStartWires.forEach { assertTrue("\"$it\"" in prompt) }
    assertTrue(
      """set "start" to exactly one of: "goal", "plan", "review", "validation", "qa", "pr", "monitor", "verify", or "pr-review-fix"""" in prompt,
    )
    assertTrue("branch:<name>" in prompt)
    assertTrue("code review" in prompt)
    assertTrue("Run phase:" in prompt)
    assertTrue("Run operation:" in prompt)
    assertTrue("\"address the local comments\"" in prompt)
    assertTrue("\"fix my diff comments\"" in prompt)
    assertTrue("fixing GitHub or PR review comments" in prompt)
    assertTrue("target beginning \"mode:remote\"" in prompt)
    assertTrue("Never set \"start\" to \"release\"" in prompt)
    assertTrue("without \"start\", the app sends its instruction as the next chat turn" in prompt)
    assertFalse("The operator will type the issue key on the start card." in prompt)
    assertFalse("\"key\":" in prompt)
    assertFalse("Offer a start action once the request is clear" in prompt)
    assertFalse("Starting a run from this chat is not available yet" in prompt)
    assertFalse("this execution" in prompt)
    assertFalse("Do not edit files" in prompt)
    assertFalse("Base branch" in scopeSystemPrompt(ChatScope.NewTask("/work/app", null)))
  }

  @Test
  fun `the new-task prompt permits goal and review start actions`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", null))

    assertTrue("Run phase:goal" in prompt)
    assertTrue("branch:<name>" in prompt)
    assertFalse("Starting a run from this chat is not available yet" in prompt)
    assertFalse("Do not include a \"start\" field" in prompt)
  }

  @Test
  fun `the execution prompt keeps its execution guidance`() {
    val prompt = scopeSystemPrompt(ChatScope.Execution("wf-1", WorkKind.Goal, "SKILL-1", "Title", "/work/app"))

    assertTrue("Execution id: wf-1" in prompt)
    assertTrue("skillbill-actions" in prompt)
    assertTrue("this execution" in prompt)
    sharedStartWires.forEach { assertTrue("\"$it\"" in prompt) }
    assertTrue(
      """set "start" to exactly one of: "goal", "plan", "review", "validation", "qa", "pr", "monitor", "verify", or "pr-review-fix"""" in prompt,
    )
    assertTrue("code review" in prompt)
    assertTrue("Run phase:" in prompt)
    assertTrue("Run operation:" in prompt)
    assertTrue("Never set \"start\" to \"release\"" in prompt)
    assertTrue("Verify findings" in prompt)
    assertTrue("must not set \"start\":\"verify\"" in prompt)
  }
}

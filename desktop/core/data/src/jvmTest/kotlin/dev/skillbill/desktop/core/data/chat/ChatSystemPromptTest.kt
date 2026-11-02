package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSystemPromptTest {
  // An agent told to act on "this execution" hunts for one.
  @Test
  fun `the new-task prompt names the checkout and action rules without any execution guidance`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", "develop"))

    assertTrue("/work/app" in prompt)
    assertTrue("Base branch: develop" in prompt)
    assertTrue("skillbill-actions" in prompt)
    assertTrue("\"mode\":\"act\",\"start\":\"goal\"" in prompt)
    assertTrue("\"start\":\"goal\",\"title\":\"" in prompt)
    listOf("goal", "plan", "review", "validation", "qa", "pr").forEach {
      assertTrue("\"$it\"" in prompt)
    }
    assertTrue("without \"start\", the app sends its instruction as the next chat turn" in prompt)
    assertTrue("only after the operator clicks it" in prompt)
    assertTrue("Do not launch skill-bill yourself" in prompt)
    assertFalse("Starting a run from this chat is not available yet" in prompt)
    assertFalse("this execution" in prompt)
    assertFalse("Do not edit files" in prompt)
    assertFalse("Base branch" in scopeSystemPrompt(ChatScope.NewTask("/work/app", null)))
  }

  @Test
  fun `the new-task prompt permits goal and review start actions`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", null))

    assertTrue("set \"start\" to one of: \"goal\", \"plan\", \"review\", \"validation\", \"qa\" or \"pr\"" in prompt)
    assertTrue("the app starts the selected run when the operator clicks it" in prompt)
    assertFalse("Starting a run from this chat is not available yet" in prompt)
    assertFalse("Do not include a \"start\" field" in prompt)
  }

  @Test
  fun `the execution prompt keeps its execution guidance`() {
    val prompt = scopeSystemPrompt(ChatScope.Execution("wf-1", WorkKind.Goal, "SKILL-1", "Title", "/work/app"))

    assertTrue("Execution id: wf-1" in prompt)
    assertTrue("skillbill-actions" in prompt)
    assertTrue("this execution" in prompt)
  }
}

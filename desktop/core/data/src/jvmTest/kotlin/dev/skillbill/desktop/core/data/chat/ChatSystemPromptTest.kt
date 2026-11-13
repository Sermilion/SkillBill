package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSystemPromptTest {
  private val sharedStartWires = NewTaskActionKind.entries.map { it.wire }

  @Test
  fun `repository-free prompt allows ordinary work and requires selection before runs`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/home/operator", null, null))

    assertTrue("Home directory: /home/operator" in prompt)
    assertTrue("Selected repository: none." in prompt)
    assertTrue("file edits and saves are allowed" in prompt)
    assertTrue("Before any goal, phase or operation" in prompt)
    assertFalse("Base branch:" in prompt)
    assertFalse("Default branch" in prompt)
    assertFalse("skillbill-actions" in prompt)
  }

  // An agent told to act on "this execution" hunts for one.
  @Test
  fun `goal key proposals require explicit acceptance and stay scoped to the selected project`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", null))
    assertTrue("propose a complete issue key and wait for the operator to accept it" in prompt)
    assertTrue("suggest its next available key" in prompt)
    assertTrue("A supplied Linear issue URL proposes its extracted key, but does not accept it" in prompt)
    assertTrue("The latest explicit selection wins" in prompt)
    assertTrue("Never borrow a prefix from another repository" in prompt)
  }

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

  // An agent without routing guidance improvises verify or demands criteria before forwarding a Linear link.
  @Test
  fun `the new-task prompt routes applicable work and verify intake through Sona`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", null))

    assertTrue("Route applicable work through Sona by default after preparation" in prompt)
    assertTrue("explicitly asks you to do it yourself" in prompt)
    assertTrue("installed skill-bill skill's current command instructions" in prompt)
    assertTrue("including one called phase verify, to operation:verify" in prompt)
    assertTrue("unless the operator explicitly requests direct work" in prompt)
    assertTrue("This route excludes \"Verify findings\" and checks of existing review findings" in prompt)
    assertTrue("original intake verbatim" in prompt)
    assertTrue("do not ask for implementation criteria first" in prompt)
    assertTrue("HEAD against origin/HEAD, mode:inline and qa:off" in prompt)
    assertTrue("only after the operator confirms" in prompt)
    assertTrue("never invent issue content" in prompt)
    assertTrue("instead of guessing or quietly doing the work yourself" in prompt)
    assertTrue("invoke it through the installed dispatcher" in prompt)
    assertTrue("invoke the dispatcher only when its instruction requests such an operation or phase" in prompt)
  }

  @Test
  fun `new task preparation advises readiness without blocking an informed operator choice`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", null))

    assertTrue("Inspect the repository and connected context" in prompt)
    assertTrue("Check required infrastructure, tooling and access" in prompt)
    assertTrue("acceptance criteria, constraints, edge cases and unknowns" in prompt)
    assertTrue("batch related questions" in prompt)
    assertTrue("Readiness advice is advisory, not a hard block" in prompt)
    assertTrue("name the specific unknowns, explain their consequences" in prompt)
    assertTrue("ask once whether they are sure they want to proceed" in prompt)
    assertTrue("offer the requested start card without repeating the readiness confirmation" in prompt)
    assertTrue("Carry unresolved questions, blockers and accepted assumptions into its instruction" in prompt)
    assertTrue("Do not invent unknowns when the task is ready" in prompt)
    assertTrue("Readiness does not need an extra confirmation when no material questions remain" in prompt)
    assertTrue("keep standalone review, validation, QA, PR, monitor and operation requests on their existing routes" in prompt)
    assertFalse("Withhold the goal card while known blockers or material questions remain unresolved" in prompt)
    assertTrue("It does not start a standalone planning run" in prompt)
    assertTrue("Only the operator clicking the inline UI start card may launch a goal" in prompt)
    assertTrue("Never launch a goal through shell commands, the skill-bill CLI, MCP tools or another agent" in prompt)
    assertTrue("Do not allocate or reserve an issue key during preparation" in prompt)
    assertTrue("perform that direct work without starting a goal" in prompt)
    assertFalse("Suggested action buttons are optional conveniences" in prompt)
    assertFalse("workflow gates" in prompt)
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
    assertFalse("Route applicable work through Sona" in prompt)
    assertFalse("Readiness advice" in prompt)
  }
}

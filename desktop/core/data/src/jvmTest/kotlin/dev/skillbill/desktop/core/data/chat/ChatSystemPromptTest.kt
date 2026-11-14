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

    assertTrue("User home: /home/operator" in prompt)
    assertTrue("Selected repository root: none" in prompt)
    assertTrue("requested file creation and edits, saving and resuming chats are allowed" in prompt)
    assertTrue("Effective working directory: /home/operator" in prompt)
    assertTrue("Do not inspect repository branches, default branches or issue-key inventories" in prompt)
    assertTrue("manual repository picker remains available after chat starts" in prompt)
    assertTrue("Do not emit an executable start card or invoke a skill-bill CLI or MCP run command" in prompt)
    assertTrue("Git metadata in home" in prompt)
    assertFalse("\"start\":\"goal\"" in prompt)
    assertFalse("Inspect the repository and connected context" in prompt)
    assertTrue("skillbill-repository" in prompt)
    assertTrue("contract_version\":\"1\"" in prompt)
    assertFalse("Base branch:" in prompt)
    assertFalse("Default branch" in prompt)
    assertTrue("skillbill-actions" in prompt)
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
  fun `configured Linear asks about ticket creation before proposing a local key`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", null))

    assertTrue("inspect the selected project's instructions and MCP configuration for a Linear connection" in prompt)
    assertTrue("ask whether to create a Linear ticket from the agreed scope before suggesting a local key" in prompt)
    assertTrue("Do not ask again when the operator already authorized ticket creation for this work" in prompt)
    assertTrue("verify its workspace and team" in prompt)
    assertTrue("check for an existing matching issue before creating a duplicate" in prompt)
    assertTrue("Let Linear assign the complete issue key" in prompt)
    assertTrue("Never predict, mint or reserve a Linear key" in prompt)
    assertTrue("Show the created ticket's link and returned key" in prompt)
    assertTrue("Approval to create a ticket is not acceptance of its returned key" in prompt)
    assertTrue("retain that acceptance" in prompt)
    assertFalse("Do not allocate or reserve an issue key during preparation; the start card handles identity" in prompt)
  }

  @Test
  fun `Linear failure and declined creation do not silently select local keys`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/app", null))

    assertTrue("report the missing tools or returned error" in prompt)
    assertTrue("Do not silently switch connections, invent ticket content or substitute a local key" in prompt)
    assertTrue("If the operator declines ticket creation, respect that choice" in prompt)
    assertTrue("an explicitly chosen local-key route where project instructions allow it" in prompt)
    assertTrue("When the project has no Linear MCP setup, use the local-key route where project instructions allow it" in prompt)
    assertFalse("ask whether to create a Linear ticket" in scopeSystemPrompt(
      ChatScope.Execution("wf-1", WorkKind.Goal, "SKILL-1", "Title", "/work/app"),
    ))
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
  fun `repository selection guidance uses distinct supplied context and the parser wire format`() {
    val prompt = scopeSystemPrompt(ChatScope.NewTask("/work/repo", "/work/repo", "main", "/home/operator"))

    assertTrue("User home: /home/operator" in prompt)
    assertTrue("Effective working directory: /work/repo" in prompt)
    assertTrue("Selected repository root: /work/repo" in prompt)
    assertTrue("userHome/StudioProjects" in prompt)
    assertTrue("If more than one repository could match, ask which one and emit no selection block." in prompt)
    assertTrue("app validates a candidate and may reject it" in prompt)
    assertTrue("Selection retains this conversation and never launches or retries work" in prompt)
    assertTrue("```skillbill-repository" in prompt)
    assertTrue("\"contract_version\":\"1\",\"path\":\"/absolute/repository/root\"" in prompt)
    assertTrue("Do not combine this block with a skillbill-actions block" in prompt)
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
    assertTrue("installed `/sona` skill's current command instructions" in prompt)
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
    assertTrue("Without a selected repository, do not invoke any run through the installed dispatcher" in prompt)
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
    assertTrue("Never launch a goal through shell commands, the sona CLI, MCP tools or another agent" in prompt)
    assertTrue("Do not allocate or reserve a local issue key during preparation" in prompt)
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

  @Test
  fun `verify follow up fixes require the recorded branch even when chat runs in the primary checkout`() {
    val prompt = scopeSystemPrompt(
      ChatScope.Execution("verify-1", WorkKind.Operation, null, "Verify", "/work/main", "feature/verified-work"),
    )

    assertTrue("Verify source branch: feature/verified-work" in prompt)
    assertTrue("use the original checkout holding the recorded source branch" in prompt)
    assertTrue("Never use it for run chat fixes, even if it still exists" in prompt)
    assertTrue("ask the operator\nto resolve the checkout before editing" in prompt)
    val ordinary = scopeSystemPrompt(ChatScope.Execution("review-1", WorkKind.Phase, null, "Review", "/work/main"))
    assertFalse("Verify source branch:" in ordinary)
    assertTrue("All fixes requested through run chat belong in the run's original source checkout on its original branch" in ordinary)
  }
}

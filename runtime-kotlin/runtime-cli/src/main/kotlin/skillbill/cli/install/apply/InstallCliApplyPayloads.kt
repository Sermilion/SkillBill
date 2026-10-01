package skillbill.cli.install.apply

import skillbill.application.install.InstallService
import skillbill.contracts.SharedPayloadKeys
import skillbill.install.model.InstallAgentSkillLinkOutcome
import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallSkillStagingOutcome
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.NativeAgentApplyOutcome
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkApplyOutcome

internal fun installApplyPayload(
  plan: InstallPlan,
  result: InstallApplyResult,
  installService: InstallService,
): Map<String, Any?> =
  installPlanPayload(plan, installService) +
    mapOf(
      SharedPayloadKeys.STATUS to result.status.name.lowercase(),
      "skills" to result.skills.map(::appliedSkillPayload),
      "native_agents" to result.nativeAgents.map(::nativeAgentPayload),
      "telemetry" to telemetryPayload(result.telemetryOutcome),
      "mcp_registration" to applyMcpRegistrationPayload(result),
      "warnings" to result.warnings.map(::issuePayload),
      "failures" to result.failures.map(::issuePayload),
      "windows_symlink_outcome" to windowsOutcomePayload(result.windowsSymlinkOutcome),
    )

private fun appliedSkillPayload(skill: InstallAppliedSkill): Map<String, Any?> =
  mapOf(
    "name" to skill.skillName,
    "kind" to skill.kind.name.lowercase(),
    "platform" to skill.platformSlug,
    "source_dir" to skill.sourceDir.toString(),
    "staging" to stagingOutcomePayload(skill.staging),
    "links" to skill.links.map(::agentSkillLinkPayload),
  )

private fun stagingOutcomePayload(staging: InstallSkillStagingOutcome): Map<String, Any?> =
  mapOf(
    SharedPayloadKeys.STATUS to staging.status.name.lowercase(),
    "staging_dir" to staging.stagingDir?.toString(),
    "rendered_skill_file" to staging.renderedSkillFile?.toString(),
    "content_hash" to staging.contentHash,
    "issue" to staging.issue?.let(::issuePayload),
  )

private fun agentSkillLinkPayload(link: InstallAgentSkillLinkOutcome): Map<String, Any?> =
  mapOf(
    "agent" to link.agent.id,
    "target_dir" to link.targetDir.toString(),
    "link_path" to link.linkPath.toString(),
    "link_target" to link.linkTarget.toString(),
    SharedPayloadKeys.STATUS to link.status.name.lowercase(),
    "message" to link.message,
    "issue" to link.issue?.let(::issuePayload),
  )

private fun nativeAgentPayload(native: NativeAgentApplyOutcome): Map<String, Any?> =
  mapOf(
    "provider" to native.provider.id,
    "agent" to native.agent.id,
    SharedPayloadKeys.STATUS to native.status.name.lowercase(),
    "path" to native.path?.toString(),
    "message" to native.message,
    "issue" to native.issue?.let(::issuePayload),
  )

private fun telemetryPayload(outcome: InstallTelemetryApplyOutcome): Map<String, Any?> =
  mapOf(
    "level" to outcome.level.id,
    SharedPayloadKeys.STATUS to outcome.status.name.lowercase(),
    "config_path" to outcome.configPath?.toString(),
    "cleared_events" to outcome.clearedEvents,
    "message" to outcome.message,
    "issue" to outcome.issue?.let(::issuePayload),
  )

private fun applyMcpRegistrationPayload(result: InstallApplyResult): Map<String, Any?> =
  mapOf(
    "register" to result.mcpRegistrationIntent.register,
    "runtime_mcp_bin" to result.mcpRegistrationIntent.runtimeMcpBin?.toString(),
    "agents" to result.mcpRegistrationIntent.agents.map(SupportedAgent::id),
    "outcomes" to
      result.mcpRegistrationOutcomes.map { outcome ->
        mapOf(
          "agent" to outcome.agent.id,
          SharedPayloadKeys.STATUS to outcome.status.name.lowercase(),
          "config_path" to outcome.configPath?.toString(),
          "changed" to outcome.changed,
          "message" to outcome.message,
          "issue" to outcome.issue?.let(::issuePayload),
          "profiles" to
            outcome.profiles.map { profile ->
              mapOf(
                "config_path" to profile.configPath.toString(),
                "changed" to profile.changed,
              )
            },
        )
      },
  )

private fun windowsOutcomePayload(outcome: WindowsSymlinkApplyOutcome): Map<String, Any?> =
  mapOf(
    "preflight" to windowsPreflightPayload(outcome.preflight),
    "fallback_state" to outcome.fallbackState.name.lowercase(),
    "guidance" to outcome.guidance,
  )

private fun issuePayload(issue: InstallApplyIssue): Map<String, Any?> =
  mapOf(
    "kind" to issue.kind.name.lowercase(),
    "message" to issue.message,
    "skill_name" to issue.skillName,
    "agent" to issue.agent?.id,
    "path" to issue.path?.toString(),
    "guidance" to issue.guidance,
    "cause_class" to issue.causeClass,
  )

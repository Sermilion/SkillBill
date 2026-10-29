package skillbill.infrastructure.skills.install.plan

import skillbill.infrastructure.skills.nativeagent.support.CLAUDE_CONFIG_DIR_ENV
import java.nio.file.Path
import skillbill.infrastructure.skills.nativeagent.support.claudeConfigRoots as nativeAgentClaudeConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.claudeSkillTargets as nativeClaudeSkillTargets

internal fun claudeConfigRoot(
  home: Path,
  environment: Map<String, String>,
): Path =
  environment[CLAUDE_CONFIG_DIR_ENV]?.takeIf { it.isNotBlank() }
    ?.let { Path.of(it).toAbsolutePath().normalize() }
    ?: home.resolve(".claude")

internal fun claudeConfigRoots(
  home: Path,
  environment: Map<String, String>,
): List<Path> = nativeAgentClaudeConfigRoots(home, environment)

internal fun claudeSkillTargets(
  home: Path,
  environment: Map<String, String>,
): List<Path> = nativeClaudeSkillTargets(home, environment)

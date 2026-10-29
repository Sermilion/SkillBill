package skillbill.infrastructure.skills.install.plan

import java.nio.file.Path
import skillbill.infrastructure.skills.nativeagent.support.codexConfigRoot as nativeCodexConfigRoot
import skillbill.infrastructure.skills.nativeagent.support.codexConfigRoots as nativeCodexConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.codexSkillTargets as nativeCodexSkillTargets

internal fun codexConfigRoot(
  home: Path,
  environment: Map<String, String>,
): Path = nativeCodexConfigRoot(home, environment)

internal fun codexConfigRoots(
  home: Path,
  environment: Map<String, String>,
): List<Path> = nativeCodexConfigRoots(home, environment)

internal fun codexSkillTargets(
  home: Path,
  environment: Map<String, String>,
): List<Path> = nativeCodexSkillTargets(home, environment)

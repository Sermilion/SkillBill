package skillbill.ports.config

import java.nio.file.Path

/**
 * Domain-owned port that makes sure the project store `config.yaml` exists. The `runtime-infra/host`
 * adapter owns the file IO, including the legacy `.skill-bill/config.yaml` copy.
 */
interface ProjectConfigFilePort {
  /**
   * Returns the store config path for [repoRoot], copying a legacy config into the store first and
   * writing the default config content when neither exists.
   */
  fun ensureConfigFile(repoRoot: Path): Path
}

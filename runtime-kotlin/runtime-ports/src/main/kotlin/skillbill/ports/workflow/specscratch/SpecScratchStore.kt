package skillbill.ports.workflow.specscratch

import java.nio.file.Path

/**
 * Domain-owned port for deleting linear-mode local spec scratch (`{KEY}/` under the project store `specs/` root,
 * or under a legacy checkout `.feature-specs/`; `skill-bill project path` prints the store).
 * Deletion is the only mutation: linear-mode artifacts are never committed and are removed on
 * terminal success. Every operation is idempotent — an already-absent path is a no-op, never an
 * error — so a re-run after partial deletion stays safe. No Linear client ever crosses this seam;
 * rehydrate is agent-side MCP only.
 */
interface SpecScratchStore {
  /** Deletes a single spec file if present; a missing file is a no-op. */
  fun deleteFileIfExists(path: Path)

  /** Recursively deletes a spec scratch directory if present; a missing directory is a no-op. */
  fun deleteDirectoryIfExists(directory: Path)
}

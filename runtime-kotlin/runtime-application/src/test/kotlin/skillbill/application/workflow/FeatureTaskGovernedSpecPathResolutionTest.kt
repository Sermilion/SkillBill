package skillbill.application.workflow

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskGovernedSpecPathResolutionTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-governed-path-repo")
  private val storeRoot: Path = Files.createTempDirectory("skillbill-governed-path-store")

  init {
    Files.createDirectories(repoRoot.resolve(".git"))
  }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    storeRoot.toFile().deleteRecursively()
  }

  @Test
  fun `an absolute store spec resolves to its specs relative form`() {
    val result = resolve(storeRoot.resolve("specs/SKILL-9-example/spec.md"))

    assertEquals(FeatureTaskGovernedSpecPathResult.Ok("specs/SKILL-9-example/spec.md"), result)
  }

  @Test
  fun `a relative specs path resolves against the store root`() {
    val result = resolve(Path.of("specs/SKILL-9-example/spec.md"))

    assertEquals(FeatureTaskGovernedSpecPathResult.Ok("specs/SKILL-9-example/spec.md"), result)
  }

  @Test
  fun `a legacy feature specs path keeps resolving against the checkout`() {
    val result = resolve(Path.of(".feature-specs/SKILL-9-example/spec.md"))

    assertEquals(FeatureTaskGovernedSpecPathResult.Ok(".feature-specs/SKILL-9-example/spec.md"), result)
  }

  @Test
  fun `an absolute legacy spec resolves to its repository relative form`() {
    val result = resolve(repoRoot.resolve(".feature-specs/SKILL-9-example/spec.md"))

    assertEquals(FeatureTaskGovernedSpecPathResult.Ok(".feature-specs/SKILL-9-example/spec.md"), result)
  }

  @Test
  fun `a path outside the store and the checkout is reported as outside the repository`() {
    val outside = Files.createTempDirectory("skillbill-governed-path-outside")
    try {
      val result = resolve(outside.resolve("specs/SKILL-9-example/spec.md"))

      assertIs<FeatureTaskGovernedSpecPathResult.OutsideRepository>(result)
    } finally {
      outside.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a store path that is not a markdown spec is invalid`() {
    val result = resolve(storeRoot.resolve("specs/SKILL-9-example/notes.txt"))

    assertEquals(FeatureTaskGovernedSpecPathResult.InvalidGovernedPath, result)
  }

  @Test
  fun `a legacy path that is not a markdown spec is invalid`() {
    val result = resolve(Path.of(".feature-specs/SKILL-9-example/notes.txt"))

    assertEquals(FeatureTaskGovernedSpecPathResult.InvalidGovernedPath, result)
  }

  @Test
  fun `a checkout path outside the legacy spec directory is reported as outside the repository`() {
    val result = resolve(Path.of("docs/SKILL-9-example/spec.md"))

    assertIs<FeatureTaskGovernedSpecPathResult.OutsideRepository>(result)
  }

  private fun resolve(specPath: Path): FeatureTaskGovernedSpecPathResult =
    resolveFeatureTaskGovernedSpecPath(
      TestRepositoryEnclosingRoot,
      FixedProjectStorePort(storeRoot),
      repoRoot,
      specPath,
    )
}

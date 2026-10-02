package skillbill.application.scaffold

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.SupportedAgent
import skillbill.ports.skillremove.SkillRemoveFileSystem
import skillbill.skillremove.SkillBillRollbackException
import skillbill.skillremove.model.AgentSymlinkUnlink
import skillbill.skillremove.model.AppliedCascade
import skillbill.skillremove.model.ManifestEdit
import skillbill.skillremove.model.ReadmeCatalogEdit
import skillbill.skillremove.model.SkillRemovalPreview
import skillbill.skillremove.model.SkillRemovalRefusalReason
import skillbill.skillremove.model.SkillRemovalRequest
import skillbill.skillremove.model.SkillRemovalResult
import skillbill.skillremove.model.SkillRemovalTarget
import kotlin.test.assertIs

class SkillRemoveTest {
  @Test
  fun `previewRemoval HorizontalSkill returns dossier with cascaded skill names`() {
    val fs =
      FakeSkillRemoveFileSystem(
        cascadedNames = listOf("bill-kmp-foo", "bill-kmp-foo-ui"),
        filesystemPaths =
          listOf(
            "skills/bill-foo",
            "platform-packs/kmp/code-review/bill-kmp-foo",
            "platform-packs/kmp/code-review/bill-kmp-foo-ui",
          ),
        symlinks =
          listOf(
            AgentSymlinkUnlink(SupportedAgent.CLAUDE, "/home/u/.claude/agents/bill-foo.md"),
            AgentSymlinkUnlink(SupportedAgent.CODEX, "/home/u/.codex/agents/bill-foo.md"),
          ),
      )
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo", allowShipped = true),
        repoRootAbsolutePath = "/repo",
      )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertEquals(listOf("bill-foo", "bill-kmp-foo", "bill-kmp-foo-ui"), preview.cascadedSkillNames)
    assertEquals(3, preview.filesystemPaths.size)
    assertEquals("skills/bill-foo", preview.skillDirRoot)
    assertEquals(2, preview.agentSymlinkUnlinks.size)
  }

  @Test
  fun `previewRemoval refuses bill-prefixed horizontal product skill without allowShipped`() {
    val fs = FakeSkillRemoveFileSystem()
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = false),
        repoRootAbsolutePath = "/repo",
      )
    val refusal = assertIs<SkillRemovalResult.Refused>(SkillRemove(fs).previewRemoval(request))
    assertEquals(SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED, refusal.reason)
    assertTrue("--allow-shipped" in refusal.message)
    assertTrue("allowShipped" !in refusal.message)
  }

  @Test
  fun `executeRemoval maps a shipped skill refusal to Failed carrying the retained exception name`() {
    val fs = FakeSkillRemoveFileSystem()
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = false),
        repoRootAbsolutePath = "/repo",
      )
    val result = assertIs<SkillRemovalResult.Failed>(SkillRemove(fs).executeRemoval(request))
    assertEquals("SkillRemovalRefusedException", result.exceptionName)
    assertEquals(true, result.rollbackComplete)
    assertEquals(
      "Refusing to remove shipped surface 'bill-code-review' without --allow-shipped.",
      result.exceptionMessage,
    )
  }

  @Test
  fun `previewRemoval PlatformPack lists paired skills tree and per-provider symlinks`() {
    val fs =
      FakeSkillRemoveFileSystem(
        filesystemPaths = listOf("platform-packs/my-platform", "skills/my-platform"),
        symlinks =
          SupportedAgent.values().map { provider ->
            AgentSymlinkUnlink(provider, "/home/u/agents/bill-my-platform-*.md")
          },
      )
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.PlatformPack(platform = "my-platform"),
        repoRootAbsolutePath = "/repo",
      )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertEquals(listOf("platform-packs/my-platform", "skills/my-platform"), preview.filesystemPaths)
    assertEquals(SupportedAgent.values().size, preview.agentSymlinkUnlinks.size)
    assertEquals(emptyList<String>(), preview.cascadedSkillNames)
  }

  @Test
  fun `previewRemoval AddOn returns single-path dossier`() {
    val fs =
      FakeSkillRemoveFileSystem(
        filesystemPaths = listOf("platform-packs/kmp/addons/my-addon.md"),
      )
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.AddOn(relativePath = "platform-packs/kmp/addons/my-addon.md"),
        repoRootAbsolutePath = "/repo",
      )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertEquals(listOf("platform-packs/kmp/addons/my-addon.md"), preview.filesystemPaths)
    assertTrue(preview.manifestEdits.isEmpty())
    assertTrue(preview.agentSymlinkUnlinks.isEmpty())
  }

  @Test
  fun `previewRemoval ExternalAddOn returns external source dossier`() {
    val fs =
      FakeSkillRemoveFileSystem(
        filesystemPaths = listOf("/external/addons/my-addon.md"),
      )
    val request =
      SkillRemovalRequest(
        target =
          SkillRemovalTarget.ExternalAddOn(
            sourceRootAbsolutePath = "/external/addons",
            platform = "kmp",
            fileName = "my-addon.md",
          ),
        repoRootAbsolutePath = "/repo",
      )

    val preview = SkillRemove(fs).previewRemoval(request).preview

    assertEquals(listOf("/external/addons/my-addon.md"), preview.filesystemPaths)
    assertEquals("/external/addons/my-addon.md", preview.skillDirRoot)
    assertTrue(preview.manifestEdits.isEmpty())
    assertTrue(preview.agentSymlinkUnlinks.isEmpty())
  }

  @Test
  fun `previewRemoval refuses dot-bill-shared horizontal skill`() {
    val fs = FakeSkillRemoveFileSystem()
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = ".bill-shared", allowShipped = true),
        repoRootAbsolutePath = "/repo",
      )
    val refusal = assertIs<SkillRemovalResult.Refused>(SkillRemove(fs).previewRemoval(request))
    assertEquals(SkillRemovalRefusalReason.BILL_SHARED_PROTECTED, refusal.reason)
  }

  @Test
  fun `previewRemoval treats kotlin as a normal horizontal skill name after pre-shell pruning`() {
    val fs = FakeSkillRemoveFileSystem()
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "kotlin", allowShipped = false),
        repoRootAbsolutePath = "/repo",
      )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertNotNull(preview)
    assertEquals(listOf("kotlin"), preview.cascadedSkillNames)
  }

  @Test
  fun `previewRemoval treats kmp as a normal horizontal skill name after pre-shell pruning`() {
    val fs = FakeSkillRemoveFileSystem()
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "kmp", allowShipped = false),
        repoRootAbsolutePath = "/repo",
      )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertNotNull(preview)
    assertEquals(listOf("kmp"), preview.cascadedSkillNames)
  }

  @Test
  fun `previewRemoval accepts shipped kotlin platform pack without allowShipped`() {
    val fs = FakeSkillRemoveFileSystem(filesystemPaths = listOf("platform-packs/kotlin"))
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.PlatformPack(platform = "kotlin", allowShipped = false),
        repoRootAbsolutePath = "/repo",
      )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertNotNull(preview)
    assertEquals(listOf("platform-packs/kotlin"), preview.filesystemPaths)
  }

  @Test
  fun `previewRemoval refuses dot-bill-shared platform pack`() {
    val fs = FakeSkillRemoveFileSystem()
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.PlatformPack(platform = ".bill-shared", allowShipped = true),
        repoRootAbsolutePath = "/repo",
      )
    val refusal = assertIs<SkillRemovalResult.Refused>(SkillRemove(fs).previewRemoval(request))
    assertEquals(SkillRemovalRefusalReason.BILL_SHARED_PROTECTED, refusal.reason)
  }

  @Test
  fun `previewRemoval accepts kotlin when allowShipped is true`() {
    val fs = FakeSkillRemoveFileSystem(filesystemPaths = listOf("skills/custom-kotlin"))
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "kotlin", allowShipped = true),
        repoRootAbsolutePath = "/repo",
      )
    val preview = SkillRemove(fs).previewRemoval(request).preview
    assertNotNull(preview)
    assertEquals(listOf("skills/custom-kotlin"), preview.filesystemPaths)
  }

  @Test
  fun `executeRemoval Failed maps non-rollback-aware throwable to rollbackComplete=false`() {
    val fs =
      FakeSkillRemoveFileSystem(
        filesystemPaths = listOf("skills/bill-foo"),
        applyThrows = RuntimeException("disk on fire"),
      )
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo", allowShipped = true),
        repoRootAbsolutePath = "/repo",
      )
    val result = SkillRemove(fs).executeRemoval(request) as SkillRemovalResult.Failed
    assertEquals(false, result.rollbackComplete)
    assertEquals("disk on fire", result.exceptionMessage)
  }

  @Test
  fun `executeRemoval Failed maps SkillBillRollbackException to rollbackComplete=false`() {
    val fs =
      FakeSkillRemoveFileSystem(
        filesystemPaths = listOf("skills/bill-foo"),
        applyThrows = SkillBillRollbackException("rollback failed"),
      )
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo", allowShipped = true),
        repoRootAbsolutePath = "/repo",
      )
    val result = SkillRemove(fs).executeRemoval(request) as SkillRemovalResult.Failed
    assertEquals(false, result.rollbackComplete)
  }

  @Test
  fun `executeRemoval Failed maps generic SkillBillRuntimeException to rollbackComplete=true`() {
    val fs =
      FakeSkillRemoveFileSystem(
        filesystemPaths = listOf("skills/bill-foo"),
        applyThrows =
          SkillBillRuntimeException(
            RemoveTestFailureCode.APPLY_FAILED,
            "test",
          ),
      )
    val request =
      SkillRemovalRequest(
        target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-foo", allowShipped = true),
        repoRootAbsolutePath = "/repo",
      )
    val result = SkillRemove(fs).executeRemoval(request) as SkillRemovalResult.Failed
    assertEquals(true, result.rollbackComplete)
  }
}

private enum class RemoveTestFailureCode : RuntimeFailureCode {
  APPLY_FAILED,
}

private val SkillRemovalResult.preview: SkillRemovalPreview
  get() = assertIs<SkillRemovalResult.Preview>(this).preview

private class FakeSkillRemoveFileSystem(
  private val cascadedNames: List<String> = emptyList(),
  private val filesystemPaths: List<String> = emptyList(),
  private val symlinks: List<AgentSymlinkUnlink> = emptyList(),
  private val manifestEdits: List<ManifestEdit> = emptyList(),
  private val readmeEdits: List<ReadmeCatalogEdit> = emptyList(),
  private val applyThrows: Throwable? = null,
) : SkillRemoveFileSystem {
  override fun resolveCascadeFilesystemPaths(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<String> = filesystemPaths

  override fun discoverCascadedSkillNames(request: SkillRemovalRequest): List<String> = cascadedNames

  override fun targetExists(request: SkillRemovalRequest): Boolean = filesystemPaths.isNotEmpty()

  override fun planManifestEdits(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<ManifestEdit> = manifestEdits

  override fun planAgentSymlinkUnlinks(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<AgentSymlinkUnlink> = symlinks

  override fun planReadmeCatalogEdits(request: SkillRemovalRequest): List<ReadmeCatalogEdit> = readmeEdits

  override fun applyCascade(
    request: SkillRemovalRequest,
    preview: SkillRemovalPreview,
  ): AppliedCascade {
    applyThrows?.let { throw it }
    return AppliedCascade(
      removedPaths = preview.filesystemPaths,
      editedManifests = preview.manifestEdits.map { it.manifestPath },
      unlinkedSymlinks = preview.agentSymlinkUnlinks.map { it.path },
    )
  }
}

package skillbill.application

import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskWorkflowMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FeatureTaskExecutionIdentityPolicyTest {
  @Test
  fun `lookup request accepts a canonical repository identity`() {
    assertEquals(
      "SKILL-129",
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = " skill-129 ",
        repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      ),
    )
  }

  @Test
  fun `lookup request accepts a digit-leading tracker key`() {
    assertEquals(
      "0AC-11",
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = " 0ac-11 ",
        repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      ),
    )
  }

  @Test
  fun `lookup request accepts a tracker key that is not prefix-number`() {
    assertEquals(
      "BACKLOG-ITEM",
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = "backlog-item",
        repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      ),
    )
  }

  @Test
  fun `bare absolute path is rejected with the required prefix and the received value`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskExecutionIdentityPolicy.validateLookupRequest("SKILL-129", "/srv/repo")
      }

    assertContains(error.message.orEmpty(), FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX)
    assertContains(error.message.orEmpty(), "'/srv/repo'")
  }

  @Test
  fun `control-bearing issue key names the bound and the received value`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
          issueKey = "SKILL-129\nspoofed",
          repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
        )
      }

    assertContains(error.message.orEmpty(), "no control characters")
    assertContains(error.message.orEmpty(), "SKILL-129")
  }

  @Test
  fun `echoed value keeps newline injection out of the failure message`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskExecutionIdentityPolicy.validateLookupRequest("SKILL-129", "/srv/repo\nrepository_identity is fine")
      }

    assertFalse(error.message.orEmpty().contains('\n'), error.message.orEmpty())
    assertContains(error.message.orEmpty(), "\\n")
  }

  @Test
  fun `identity accepts a project store spec path`() {
    FeatureTaskExecutionIdentityPolicy.validate(identity("specs/SKILL-129-example/spec.md"))
  }

  @Test
  fun `identity keeps accepting a legacy feature specs path`() {
    FeatureTaskExecutionIdentityPolicy.validate(identity(".feature-specs/SKILL-129-example/spec.md"))
  }

  @Test
  fun `identity rejects a governed path outside both spec directories and names both forms`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskExecutionIdentityPolicy.validate(identity("docs/SKILL-129-example/spec.md"))
      }

    assertContains(error.message.orEmpty(), "specs/<...>.md")
    assertContains(error.message.orEmpty(), ".feature-specs/<...>.md")
    assertContains(error.message.orEmpty(), "'docs/SKILL-129-example/spec.md'")
  }

  @Test
  fun `identity rejects a store spec path with a parent segment`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskExecutionIdentityPolicy.validate(identity("specs/../spec.md"))
    }
  }

  @Test
  fun `identity rejects a store path that is not a markdown spec`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskExecutionIdentityPolicy.validate(identity("specs/SKILL-129-example/spec.txt"))
    }
  }

  private fun identity(governedSpecPath: String): FeatureTaskExecutionIdentity =
    FeatureTaskExecutionIdentity(
      workflowId = "wftr-policy-test",
      normalizedIssueKey = "SKILL-129",
      repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      governedSpecPath = governedSpecPath,
      mode = FeatureTaskWorkflowMode.RUNTIME,
    )
}

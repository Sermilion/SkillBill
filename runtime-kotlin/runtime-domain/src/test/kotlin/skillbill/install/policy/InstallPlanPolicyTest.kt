package skillbill.install.policy

import skillbill.error.shellcontent.MissingBaselinePlatformSelectionError
import skillbill.install.model.InstallAgentDefaultTarget
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.model.selectedPlatformSlugs
import skillbill.model.FileLocation
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstallPlanPolicyTest {
  @Test
  fun `manual plan draft resolves selected platform skills agent defaults and MCP intent`() {
    val input =
      policyInput(
        request =
          request(
            agentSelection =
              InstallAgentSelection(
                mode = InstallAgentSelectionMode.MANUAL,
                manualAgents = setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE),
              ),
            targetPaths =
              targetPaths(
                agentTargets =
                  listOf(
                    InstallAgentTarget(
                      agent = SupportedAgent.CLAUDE,
                      path = path("/manual/claude"),
                      source = InstallAgentTargetSource.DETECTED,
                    ),
                  ),
              ),
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("kotlin"),
              ),
          ),
      )

    val draft = InstallPlanPolicy.buildPlanDraft(input)

    assertEquals(listOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX), draft.agents.map(InstallAgentTarget::agent))
    assertEquals(
      listOf(InstallAgentTargetSource.MANUAL, InstallAgentTargetSource.MANUAL),
      draft.agents.map { it.source },
    )
    assertEquals(path("/manual/claude"), draft.agents.first { it.agent == SupportedAgent.CLAUDE }.path)
    assertEquals(path("/home/.codex/skills"), draft.agents.first { it.agent == SupportedAgent.CODEX }.path)
    assertEquals(listOf("kotlin"), draft.selectedPlatformSlugs)
    assertEquals(listOf("bill-code-review", "bill-kotlin-code-review"), draft.skills.map(InstallPlanSkill::name))
    assertEquals(listOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX), draft.mcpRegistrationIntent.agents)
    assertEquals(input.request.targetPaths.copy(agentTargets = draft.agents), draft.installationTargetPaths)
  }

  @Test
  fun `detected plan draft prefers caller supplied detected targets and normalizes their source`() {
    val input =
      policyInput(
        request =
          request(
            agentSelection =
              InstallAgentSelection(
                mode = InstallAgentSelectionMode.DETECTED,
                detectedTargets =
                  listOf(
                    InstallAgentTarget(
                      agent = SupportedAgent.CURSOR,
                      path = path("/detected/cursor"),
                      source = InstallAgentTargetSource.MANUAL,
                    ),
                  ),
              ),
          ),
        detectedAgentTargets =
          listOf(
            InstallAgentTarget(
              agent = SupportedAgent.CODEX,
              path = path("/detected/codex"),
              source = InstallAgentTargetSource.DETECTED,
            ),
          ),
      )

    val draft = InstallPlanPolicy.buildPlanDraft(input)

    assertEquals(listOf(SupportedAgent.CURSOR), draft.agents.map(InstallAgentTarget::agent))
    assertEquals(listOf(InstallAgentTargetSource.DETECTED), draft.agents.map { target -> target.source })
    assertEquals(path("/detected/cursor"), draft.agents.single().path)
  }

  @Test
  fun `request validation rejects inconsistent platform and target selections`() {
    val selectedWithoutSlugs =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.validateRequest(
          policyInput(
            request =
              request(
                platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.SELECTED),
              ),
          ),
        )
      }
    assertContains(selectedWithoutSlugs.message.orEmpty(), "SELECTED requires at least one selected slug")

    val detectedWithManualAgents =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.validateRequest(
          policyInput(
            request =
              request(
                agentSelection =
                  InstallAgentSelection(
                    mode = InstallAgentSelectionMode.DETECTED,
                    manualAgents = setOf(SupportedAgent.CODEX),
                  ),
              ),
          ),
        )
      }
    assertContains(
      detectedWithManualAgents.message.orEmpty(),
      "Detected agent selection must not include manual agents",
    )

    val missingManualTarget =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.validateRequest(
          policyInput(
            request =
              request(
                agentSelection =
                  InstallAgentSelection(
                    mode = InstallAgentSelectionMode.MANUAL,
                    manualAgents = setOf(SupportedAgent.CODEX),
                  ),
              ),
          ).copy(defaultAgentTargets = emptyList()),
        )
      }
    assertContains(
      missingManualTarget.message.orEmpty(),
      "no explicit or default target path",
    )
  }

  @Test
  fun `request validation rejects malformed skill and platform snapshots`() {
    val blankBaseName =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.validateRequest(
          policyInput(baseSkills = listOf(baseSkill(""))),
        )
      }
    assertContains(blankBaseName.message.orEmpty(), "non-blank name")

    val blankBaseSource =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.validateRequest(
          policyInput(baseSkills = listOf(baseSkill("bill-code-review", sourceDir = path("")))),
        )
      }
    assertContains(blankBaseSource.message.orEmpty(), "sourceDir must not be blank")

    val nonPlatformSkill =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.validateRequest(
          policyInput(
            platformPacks =
              listOf(
                platformPack(
                  skills =
                    listOf(
                      baseSkill("bill-kotlin-code-review"),
                    ),
                ),
              ),
          ),
        )
      }
    assertContains(nonPlatformSkill.message.orEmpty(), "contains non-platform skill")

    val mismatchedPlatformSlug =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.validateRequest(
          policyInput(
            platformPacks =
              listOf(
                platformPack(
                  slug = "kotlin",
                  skills = listOf(platformSkill("bill-kotlin-code-review", platformSlug = "kmp")),
                ),
              ),
          ),
        )
      }
    assertContains(mismatchedPlatformSlug.message.orEmpty(), "owned by 'kmp'")
  }

  @Test
  fun `platform skill materialization plan uses policy selection without skill snapshots`() {
    val plan =
      InstallPlanPolicy.planPlatformSkillMaterialization(
        InstallPlatformSkillMaterializationRequest(
          installRequest =
            request(
              platformPackSelection =
                PlatformPackSelection(
                  mode = PlatformPackSelectionMode.SELECTED,
                  selectedSlugs = setOf("kotlin"),
                ),
            ),
          platformPacks =
            listOf(
              InstallPlatformPackDiscoverySnapshot(
                slug = "kmp",
                packRoot = path("/repo/platform-packs/kmp"),
                baselineLayers = listOf(baselineLayer(platform = "kotlin", skill = "bill-kotlin-code-review")),
              ),
              InstallPlatformPackDiscoverySnapshot(slug = "kotlin", packRoot = path("/repo/platform-packs/kotlin")),
            ),
        ),
      )

    assertEquals(listOf("kmp", "kotlin"), plan.selectedPlatformSlugs)

    val duplicateDiscovery =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.planPlatformSkillMaterialization(
          InstallPlatformSkillMaterializationRequest(
            installRequest =
              request(
                platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
              ),
            platformPacks =
              listOf(
                InstallPlatformPackDiscoverySnapshot(slug = "kotlin", packRoot = path("/repo/platform-packs/kotlin")),
                InstallPlatformPackDiscoverySnapshot(
                  slug = "kotlin",
                  packRoot = path("/repo/platform-packs/kotlin-copy"),
                ),
              ),
          ),
        )
      }
    assertContains(duplicateDiscovery.message.orEmpty(), "duplicate slug")
  }

  @Test
  fun `planning rejects unknown platforms and duplicate skill names`() {
    val unknownPlatform =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.buildPlanDraft(
          policyInput(
            request =
              request(
                platformPackSelection =
                  PlatformPackSelection(
                    mode = PlatformPackSelectionMode.SELECTED,
                    selectedSlugs = setOf("swift"),
                  ),
              ),
          ),
        )
      }
    assertContains(unknownPlatform.message.orEmpty(), "Unknown platform pack selection: swift")

    val duplicateSkill =
      assertFailsWith<IllegalArgumentException> {
        InstallPlanPolicy.buildPlanDraft(
          policyInput(
            platformPacks =
              listOf(
                platformPack(
                  slug = "duplicate",
                  skills = listOf(platformSkill("bill-code-review", platformSlug = "duplicate")),
                ),
              ),
            request =
              request(
                platformPackSelection =
                  PlatformPackSelection(
                    mode = PlatformPackSelectionMode.SELECTED,
                    selectedSlugs = setOf("duplicate"),
                  ),
              ),
          ),
        )
      }
    assertContains(duplicateSkill.message.orEmpty(), "duplicate skill name")
    assertContains(duplicateSkill.message.orEmpty(), "bill-code-review")
  }

  @Test
  fun `PD8 guard fails when a selected pack declares a required baseline in an unselected pack`() {
    val kmpPack =
      platformPack(
        slug = "kmp",
        skills = listOf(platformSkill("bill-kmp-code-review", platformSlug = "kmp")),
        baselineLayers = listOf(baselineLayer(platform = "kotlin", skill = "bill-kotlin-code-review")),
      )
    val kotlinPack = platformPack(slug = "kotlin")
    val input =
      policyInput(
        request =
          request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("kmp"),
              ),
          ),
        platformPacks = listOf(kmpPack, kotlinPack),
      )

    val error = assertFailsWith<MissingBaselinePlatformSelectionError> { InstallPlanPolicy.buildPlanDraft(input) }
    assertEquals("kmp", error.selectingSlug)
    assertEquals("kotlin", error.requiredBaselineSlug)
    assertContains(error.declaringManifestPath, "platform-packs/kmp/platform.yaml")
    assertContains(error.message.orEmpty(), "'kotlin' is not in the selection")
  }

  @Test
  fun `PD8 guard passes when the required baseline pack is also selected`() {
    val kmpPack =
      platformPack(
        slug = "kmp",
        skills = listOf(platformSkill("bill-kmp-code-review", platformSlug = "kmp")),
        baselineLayers = listOf(baselineLayer(platform = "kotlin", skill = "bill-kotlin-code-review")),
      )
    val kotlinPack = platformPack(slug = "kotlin")
    val input =
      policyInput(
        request =
          request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("kmp", "kotlin"),
              ),
          ),
        platformPacks = listOf(kmpPack, kotlinPack),
      )

    val draft = InstallPlanPolicy.buildPlanDraft(input)
    assertEquals(listOf("kmp", "kotlin"), draft.selectedPlatformSlugs)
  }

  @Test
  fun `selecting a baseline pack installs required composed packs transitively`() {
    val kotlinPack = platformPack(slug = "kotlin")
    val kmpPack =
      platformPack(
        slug = "kmp",
        skills = listOf(platformSkill("bill-kmp-code-review", platformSlug = "kmp")),
        baselineLayers = listOf(baselineLayer(platform = "kotlin", skill = "bill-kotlin-code-review")),
      )
    val input =
      policyInput(
        request =
          request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("kotlin"),
              ),
          ),
        platformPacks = listOf(kmpPack, kotlinPack),
      )

    val draft = InstallPlanPolicy.buildPlanDraft(input)

    assertEquals(listOf("kmp", "kotlin"), draft.selectedPlatformSlugs)
  }

  @Test
  fun `PD8 guard passes under ALL selection even when a baseline layer points to another pack`() {
    val kmpPack =
      platformPack(
        slug = "kmp",
        skills = listOf(platformSkill("bill-kmp-code-review", platformSlug = "kmp")),
        baselineLayers = listOf(baselineLayer(platform = "kotlin", skill = "bill-kotlin-code-review")),
      )
    val kotlinPack = platformPack(slug = "kotlin")
    val input =
      policyInput(
        request =
          request(
            platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
          ),
        platformPacks = listOf(kmpPack, kotlinPack),
      )

    val draft = InstallPlanPolicy.buildPlanDraft(input)
    assertEquals(listOf("kmp", "kotlin"), draft.selectedPlatformSlugs.sorted())
  }

  @Test
  fun `PD8 guard is unaffected by packs without baseline layers`() {
    val pythonPack = platformPack(slug = "python")
    val input =
      policyInput(
        request =
          request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("python"),
              ),
          ),
        platformPacks = listOf(pythonPack, platformPack(slug = "kotlin")),
      )

    val draft = InstallPlanPolicy.buildPlanDraft(input)
    assertEquals(listOf("python"), draft.selectedPlatformSlugs)
  }

  @Test
  fun `review fallback pack is selected only when the pack sidecar parent is a base skill`() {
    val platformPacks = listOf(platformPack(slug = "generic"), platformPack(slug = "kotlin"))

    val withParent =
      policyInput(
        baseSkills = listOf(baseSkill(PACK_SIDECAR_PARENT_SKILL)),
        platformPacks = platformPacks,
        resolvedReviewFallbackSlug = "generic",
      )
    assertEquals(listOf("generic"), selectedPlatformSlugs(withParent))

    val withoutParent =
      policyInput(
        baseSkills = listOf(baseSkill("bill-code-review")),
        platformPacks = platformPacks,
        resolvedReviewFallbackSlug = "generic",
      )
    assertEquals(emptyList(), selectedPlatformSlugs(withoutParent))
  }

  private fun policyInput(
    request: InstallPlanRequest = request(),
    baseSkills: List<InstallPlanSkill> = listOf(baseSkill("bill-code-review")),
    platformPacks: List<InstallPlatformPackSnapshot> = listOf(platformPack()),
    resolvedReviewFallbackSlug: String? = null,
    detectedAgentTargets: List<InstallAgentTarget> = emptyList(),
  ): InstallPolicyInput =
    InstallPolicyInput(
      request = request,
      baseSkills = baseSkills,
      platformPacks = platformPacks,
      resolvedReviewFallbackSlug = resolvedReviewFallbackSlug,
      detectedAgentTargets = detectedAgentTargets,
      defaultAgentTargets = defaultAgentTargets(),
    )

  private fun defaultAgentTargets(): List<InstallAgentDefaultTarget> =
    listOf(
      InstallAgentDefaultTarget(SupportedAgent.CLAUDE, path("/home/.claude/skills")),
      InstallAgentDefaultTarget(SupportedAgent.CODEX, path("/home/.codex/skills")),
      InstallAgentDefaultTarget(SupportedAgent.JUNIE, path("/home/.junie/skills")),
      InstallAgentDefaultTarget(SupportedAgent.CURSOR, path("/home/.cursor/skills")),
    )

  private fun request(
    agentSelection: InstallAgentSelection =
      InstallAgentSelection(
        mode = InstallAgentSelectionMode.MANUAL,
        manualAgents = setOf(SupportedAgent.CODEX),
      ),
    platformPackSelection: PlatformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
    targetPaths: InstallationTargetPaths = targetPaths(),
    mcpRegistrationChoice: McpRegistrationChoice =
      McpRegistrationChoice(
        register = true,
        runtimeMcpBin = path("/runtime-mcp"),
      ),
  ): InstallPlanRequest =
    InstallPlanRequest(
      repoRoot = path("/repo"),
      home = path("/home"),
      agentSelection = agentSelection,
      platformPackSelection = platformPackSelection,
      telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      mcpRegistrationChoice = mcpRegistrationChoice,
      runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = path("/home/.skill-bill/runtime")),
      targetPaths = targetPaths,
      windowsSymlinkPreflight =
        WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
    )

  private fun targetPaths(agentTargets: List<InstallAgentTarget> = emptyList()): InstallationTargetPaths =
    InstallationTargetPaths(
      skillsRoot = path("/repo/skills"),
      platformPacksRoot = path("/repo/platform-packs"),
      agentTargets = agentTargets,
    )

  private fun platformPack(
    slug: String = "kotlin",
    skills: List<InstallPlanSkill> = listOf(platformSkill("bill-kotlin-code-review", platformSlug = slug)),
    baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
  ): InstallPlatformPackSnapshot =
    InstallPlatformPackSnapshot(
      slug = slug,
      packRoot = path("/repo/platform-packs/$slug"),
      skills = skills,
      baselineLayers = baselineLayers,
    )

  private fun baselineLayer(
    platform: String,
    skill: String,
    required: Boolean = true,
  ): CodeReviewBaselineLayer =
    CodeReviewBaselineLayer(
      platform = platform,
      skill = skill,
      scope = CodeReviewCompositionScope.SameReviewScope,
      required = required,
      mode = CodeReviewCompositionMode.KmpBaseline,
    )

  private fun baseSkill(
    name: String,
    sourceDir: FileLocation = path("/repo/skills/$name"),
  ): InstallPlanSkill =
    InstallPlanSkill(
      name = name,
      sourceDir = sourceDir,
      kind = InstallPlanSkillKind.BASE,
    )

  private fun platformSkill(
    name: String,
    platformSlug: String,
  ): InstallPlanSkill =
    InstallPlanSkill(
      name = name,
      sourceDir = path("/repo/platform-packs/$platformSlug/code-review/$name"),
      kind = InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = platformSlug,
    )

  private fun path(value: String): FileLocation = FileLocation(value)
}

package skillbill.engine.featuretask.persist.acceptance

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.effectivePolicyDigest
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdoptionComposition
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedFeatureTaskRuntimeExecutionSettings
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

internal object AcceptanceCriterionRepairHistoricalTuples {
  fun supports(
    composition: AcceptanceCriterionRepairAdoptionComposition,
    revision: Int,
  ): Boolean = TUPLES.containsKey(composition to revision)

  fun matches(
    recorded: ResolvedPhaseExecutionPlan,
    inputs: EffectiveGatePolicyInputs,
    composition: AcceptanceCriterionRepairAdoptionComposition,
    codec: FeatureTaskRuntimeExecutionPlanCodec,
  ): Boolean {
    val revision = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT }?.semanticRevision
    val historical = TUPLES[composition to revision] ?: return false
    val versionSupported =
      recorded.contractVersion == PREVIOUS_DESCRIPTOR_VERSION ||
        revision == PROSE_PLAN_REVISION && recorded.contractVersion == ASSIGNED_DESCRIPTOR_VERSION
    if (!versionSupported) return false
    val payload =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.parseValue(String(codec.encode(recorded), Charsets.UTF_8)),
        ),
      )
    val shape = payload.filterKeys { it !in VARIABLE_DESCRIPTOR_FIELDS }
    return recorded.effectivePolicySettings ==
      ResolvedFeatureTaskRuntimeExecutionSettings(inputs.validationDepth, inputs.phaseTimeoutMillis) &&
      sha256Hex(JsonCodec.valueToJsonString(shape)) == historical.descriptorShapeDigest &&
      policiesMatch(recorded, inputs, historical)
  }

  private fun policiesMatch(
    recorded: ResolvedPhaseExecutionPlan,
    inputs: EffectiveGatePolicyInputs,
    historical: HistoricalTuple,
  ): Boolean {
    val expected =
      COMMON_POLICIES +
        mapOf(
          RETRY_BUDGETS to historical.retryPolicyDigest,
          RESUME_BUDGETS to historical.resumePolicyDigest,
          GATE_COMMANDS to effectivePolicyDigest(inputs.canonicalInputs()),
        )
    return recorded.effectivePolicies.size == expected.size &&
      recorded.effectivePolicies.all {
        it.semanticRevision == 1 && expected[it.id] == it.semanticDigest
      }
  }

  private data class HistoricalTuple(
    val descriptorShapeDigest: String,
    val retryPolicyDigest: String,
    val resumePolicyDigest: String,
  )

  private const val PROSE_PLAN_REVISION = 3
  private const val PREVIOUS_DESCRIPTOR_VERSION = "0.1"
  private const val ASSIGNED_DESCRIPTOR_VERSION = "0.2"
  private const val RETRY_BUDGETS = "retry-budgets"
  private const val RESUME_BUDGETS = "resume-budgets"
  private const val GATE_COMMANDS = "gate-commands"
  private val VARIABLE_DESCRIPTOR_FIELDS =
    setOf(
      Keys.CONTRACT_VERSION,
      Keys.EFFECTIVE_POLICIES,
      Keys.EFFECTIVE_POLICY_SETTINGS,
      Keys.STEP_LAUNCH_ASSIGNMENTS,
    )
  private val COMMON_POLICIES =
    mapOf(
      "acceptance-audit" to "b26d899da54948535b5032ce9171aa9e2cc460659698bb8f126c51c45fa6cc28",
      "checkpoint-ownership" to "cd129b6c9e4dccbeba5cbc4fd73746f976906492c8b471a3693cff5b0f51f854",
      "finalization" to "512b08cc666b421bdf800c57e58dcf40266c3f205eec15194ca3209e4af8f95c",
      "receipt-interpretation" to "3401f5c2bfede12975f9bf87a040793fd9188e46ba776f207225977a806493b8",
      "review-invalidation" to "41ca4ed8f42fa1f490a41c6cf995461a108f931f9a9f5a6f896cea579fb889a8",
    )
  private val TUPLES =
    mapOf(
      (AcceptanceCriterionRepairAdoptionComposition.STANDALONE to 1) to
        HistoricalTuple(
          "5d528d3176a6ae49361d65262c0d728dbb3880b9c1dd5722a88929681b32ab8a",
          "595112dcc1ef7d1bbcbfc7d903fe01649ae1f3ecb9e1b1a735e1c663a6d2f9bd",
          "d589711414f8eced537c44bdff52703257f945efa0dc8193b8fbe84d7611685e",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.STANDALONE to 2) to
        HistoricalTuple(
          "6139b231f6d5d02c5b36d57fc1579b4899d102e45229651f76cc154eee3adbdf",
          "d2c087aaa0ed089439a0aa6e30bba6f5cd2a84115a01021d6b0f286c63d7f3ce",
          "4c1348638cacc9480570bef176905f555d2ba3cc41ec2f2f37c3f3536a939391",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.STANDALONE to PROSE_PLAN_REVISION) to
        HistoricalTuple(
          "95b0435a52adc6e9cfc7cd3f697c8eff61e7d5423b97190589cf87a8ec3982a7",
          "8c36c379450376c719e798977a99412f768bb83304e51272d428ab57f94fc177",
          "14d71790f2ddeea8efd9a3cadfaf849c0c29e42ae289c54b5ea094687ba3feed",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD to 1) to
        HistoricalTuple(
          "2882fadafaaddfb50b06604d911a91ad0f4b319d7e736f6b89f8f7352cbdff6a",
          "936be9d703ed34f3ca0fd0978dbe3a110b82770107c82bfdfac3846ec45696ee",
          "3bfc25ee9af1409be3f2c9202869b7f92424b0c15d36e719f7927b49cb9ab03f",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD to 2) to
        HistoricalTuple(
          "ef7e4a527a441ed63b4816bbd54981ab3f390179b9df07b170889b2454047f43",
          "af940243352afcbfdc06af01f3f76ea88ec2f79b42a780641c3d5321e5c21208",
          "fea8e40513df7371bf63ab68418b00adc2e8c0834c1da967cc8b14eba1c45c87",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD to PROSE_PLAN_REVISION) to
        HistoricalTuple(
          "a578122cc2d44e8807e2502fb09e921eb3555076dd25788c168cc95b16cc76d1",
          "5a311a6158c6f0fd51be9974488d4f60c1c1c1f8db9622ec585c8cd21f893369",
          "01ae2d16bafe91eebf82d0ddb0176ade23187ff54f6d2c1f2fa47e8be0c1688a",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE to 1) to
        HistoricalTuple(
          "2138181a3be059457194108d58adf2f40a402bafe0d6197eb687055bcd37f76c",
          "c2ce8bf9fc651aaebc26429fdea731cf8fe071b3b9aa4aff726365c73beb6dc3",
          "81aa70357ef9ad14e298e4813740aaffa27db83c04469afb9a8e0c6483745c35",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE to 2) to
        HistoricalTuple(
          "4bd8ad384392e92c93b3720b8bd5a60cf75e6cf0e7da3410228d185144638345",
          "d450b02b6349baed913bf7e3246061454a0263ec218ceb6ed74b1369900415cb",
          "7574e448bc6647af57b951bab0fa7272f64208b11b2ddec0fb71498d54269863",
        ),
      (AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE to PROSE_PLAN_REVISION) to
        HistoricalTuple(
          "905f9c754f631c13b44de04a2e52a284466e6110a20c8acad6aa57092f173f85",
          "bea07674b874a9ebd0205327f9ec6ec81f45df5c06c056b5f4acecb64d6d18f7",
          "ed1e393fad996096ab5b6c64ae123802064d37737c81e9710805a4cd6858e6b3",
        ),
    )
}

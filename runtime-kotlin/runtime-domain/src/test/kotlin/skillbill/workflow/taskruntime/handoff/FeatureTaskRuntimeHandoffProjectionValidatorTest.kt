package skillbill.workflow.taskruntime.handoff

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeHandoffProjectionValidatorTest {
  @Test
  fun `projection byte size equals its canonical delivered rendering`() {
    val projection =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(handoffProjectionValidatorInputs())
        .projections.single()

    assertEquals(
      projection.canonicalDeliveredRendering.toByteArray(Charsets.UTF_8).size,
      projection.utf8ByteSize,
    )
  }

  @Test
  fun `projection identity uses the resolved producer attempt`() {
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          resolvedUpstream =
            FeatureTaskRuntimeResolvedUpstreamOutputs(
              mapOf(
                HANDOFF_VALIDATOR_TEST_PRODUCER to
                  FeatureTaskRuntimePhaseOutput(
                    phaseId = HANDOFF_VALIDATOR_TEST_PRODUCER,
                    iteration = 7,
                    payload = """{"plan":"ok"}""",
                  ),
              ),
            )
        },
      )

    assertEquals(
      FeatureTaskRuntimeProducerIteration(HANDOFF_VALIDATOR_TEST_PRODUCER, 7),
      envelope.projections.single().producerIteration,
    )
  }

  @Test
  fun `a declared upstream receipt is projected within budget`() {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(handoffProjectionValidatorInputs())

    assertEquals(1, envelope.projections.size)
    val projection = envelope.projections.single()
    assertEquals("plan_receipt", projection.projectionName)
    assertEquals(
      """{"plan":"ok"}""",
      (projection.fields.single().value as FeatureTaskRuntimeHandoffProjectionValue.Text).text,
    )
  }

  @Test
  fun `a missing required source is rejected`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs { resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()) },
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.MISSING_REQUIRED_SOURCE, error.failureKind)
    assertEquals(HANDOFF_VALIDATOR_TEST_CONSUMER, error.consumerPhaseId)
    assertEquals("wftr-1", error.workflowId)
  }

  @Test
  fun `review repair projection carries upstream prose and the exact runtime checkpoint`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    val declaration =
      handoffProjectionDeclaration {
        consumerPhaseId = consumer
        sourceRef =
          FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
          )
        projectionName = "review_repair_request"
        projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST
        declaredFieldNames = listOf("value", "repository_checkpoint")
        checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH
      }
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree")
    val prose = "F-001 blocker at A.kt:1 verified; F-002 major at B.kt:1 verified."
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          consumerPhaseId = consumer
          declarations = listOf(declaration)
          resolvedUpstream =
            FeatureTaskRuntimeResolvedUpstreamOutputs(
              mapOf(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
                  proseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS, prose),
              ),
            )
          resolvedCheckpoint = checkpoint
          expectedCheckpoint = checkpoint
        },
      )

    val fields = envelope.projections.single().fields
    assertEquals(listOf("value", "repository_checkpoint"), fields.map { it.name })
    assertEquals(prose, assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(fields.first().value).text)
  }

  @Test
  fun `change receipt derives changed paths from the runtime checkpoint`() {
    val declaration =
      handoffProjectionDeclaration {
        projectionContractId =
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT
        declaredFieldNames = listOf("changed_paths", "repository_checkpoint")
        checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY
      }
    val checkpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = "current-tree",
        workingTreeOwnedPaths = listOf("src/Foo.kt", "src/FooTest.kt"),
      )

    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          declarations = listOf(declaration)
          resolvedUpstream = handoffProjectionUpstream("""{"produced_outputs":{}}""")
          resolvedCheckpoint = checkpoint
        },
      )

    val fields = envelope.projections.single().fields.associateBy { it.name }
    val changedPaths =
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
        fields.getValue("changed_paths").value,
      )
    assertEquals(listOf("src/Foo.kt", "src/FooTest.kt"), changedPaths.items)
  }
}

class FeatureTaskRuntimeHandoffProjectionValidatorFinalizationTest {
  @Test
  fun `value-only implement and plan launch all five finalization consumers without missing-key failures`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val checkpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = "tree-1",
        workingTreeOwnedPaths = listOf("src/Foo.kt"),
      )
    val upstream = valueOnlyFinalizationUpstream()
    listOf(
      def.PHASE_VALIDATE,
      def.PHASE_BUILD,
      def.PHASE_WRITE_HISTORY,
      def.PHASE_COMMIT_PUSH,
      def.PHASE_PR,
    ).forEach { consumer ->
      assertValueOnlyConsumerLaunches(consumer, upstream, checkpoint)
    }
  }

  @Test
  fun `validate and build briefings carry plan value verbatim and optional directive`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val planProse = """{"projection_kind":"executable_plan","contract_version":"0.2"}"""
    val plan = proseOutput(def.PHASE_PLAN, planProse, "plan directive")
    val audit = proseOutput(def.PHASE_AUDIT, "audit satisfied")
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("tree-1", workingTreeOwnedPaths = listOf("src/A.kt"))
    listOf(def.PHASE_VALIDATE, def.PHASE_BUILD).forEach { consumer ->
      val phaseDeclarations =
        FeatureTaskRuntimePhaseWorkflowQueries
          .phaseDeclaration(consumer, FeatureTaskRuntimeFeatureSize.MEDIUM)
          .projectionDeclarations
      val envelope =
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            consumerPhaseId = consumer
            declarations = phaseDeclarations
            resolvedUpstream =
              FeatureTaskRuntimeResolvedUpstreamOutputs(
                mapOf(def.PHASE_PLAN to plan, def.PHASE_AUDIT to audit),
              )
            resolvedCheckpoint = checkpoint
          },
        )
      val prose =
        envelope.projections.single {
          it.projectionContractId == FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE
        }
      val value =
        assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(
          prose.fields.single { it.name == "value" }.value,
        )
      assertEquals(planProse, value.text)
      val directive =
        assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(
          prose.fields.single { it.name == "directive" }.value,
        )
      assertEquals("plan directive", directive.text)
    }
  }

  @Test
  fun `write_history commit_push and pr briefings carry implement value verbatim`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val implementProse = """{"projection_kind":"implementation_receipt","contract_version":"0.2"}"""
    val implement = proseOutput(def.PHASE_IMPLEMENT, implementProse, "implement directive")
    val validate = recordedPhaseOutput(def.PHASE_VALIDATE, HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD)
    val writeHistory = recordedPhaseOutput(def.PHASE_WRITE_HISTORY, HANDOFF_VALIDATOR_HISTORY_PHASE_PAYLOAD)
    val commitPush = proseOutput(def.PHASE_COMMIT_PUSH, "pushed feat at abc")
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("tree-1", workingTreeOwnedPaths = listOf("src/A.kt"))
    listOf(def.PHASE_WRITE_HISTORY, def.PHASE_COMMIT_PUSH, def.PHASE_PR).forEach { consumer ->
      val phaseDeclarations =
        FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationWithoutSteps(
          consumer,
          FeatureTaskRuntimeFeatureSize.MEDIUM,
          setOf(def.PHASE_BUILD),
        ).projectionDeclarations
      val resolvedUpstreamOutputs =
        FeatureTaskRuntimeResolvedUpstreamOutputs(
          buildMap {
            put(def.PHASE_IMPLEMENT, implement)
            put(def.PHASE_VALIDATE, validate)
            if (consumer == def.PHASE_COMMIT_PUSH || consumer == def.PHASE_PR) {
              put(def.PHASE_WRITE_HISTORY, writeHistory)
            }
            if (consumer == def.PHASE_PR) {
              put(def.PHASE_COMMIT_PUSH, commitPush)
            }
          },
        )
      val envelope =
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            consumerPhaseId = consumer
            declarations = phaseDeclarations
            resolvedUpstream = resolvedUpstreamOutputs
            resolvedCheckpoint = checkpoint
          },
        )
      val prose =
        envelope.projections.single {
          it.projectionContractId == FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE
        }
      val value =
        assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(
          prose.fields.single { it.name == "value" }.value,
        )
      assertEquals(implementProse, value.text)
    }
  }

  @Test
  fun `changed_paths on finalization consumers come from checkpoint not receipt claims`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val plan = proseOutput(def.PHASE_PLAN, "plan prose claims src/ClaimOnly.kt")
    val implement = proseOutput(def.PHASE_IMPLEMENT, "implement prose claims src/ClaimOnly.kt")
    val audit = proseOutput(def.PHASE_AUDIT, "audit satisfied")
    val validate = recordedPhaseOutput(def.PHASE_VALIDATE, HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD)
    val writeHistory = recordedPhaseOutput(def.PHASE_WRITE_HISTORY, HANDOFF_VALIDATOR_HISTORY_PHASE_PAYLOAD)
    val checkpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = "tree-1",
        workingTreeOwnedPaths = listOf("src/Owned.kt", "src/OwnedTest.kt"),
      )
    val expectedPaths = listOf("src/Owned.kt", "src/OwnedTest.kt")

    fun changedPathsFrom(consumer: String): List<String> {
      val omittedStepIds =
        if (consumer == def.PHASE_BUILD) {
          setOf(def.PHASE_VALIDATE)
        } else {
          setOf(def.PHASE_BUILD)
        }
      val phaseDeclarations =
        FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationWithoutSteps(
          consumer,
          FeatureTaskRuntimeFeatureSize.MEDIUM,
          omittedStepIds,
        ).projectionDeclarations
      val resolvedUpstreamOutputs =
        FeatureTaskRuntimeResolvedUpstreamOutputs(
          buildMap {
            put(def.PHASE_PLAN, plan)
            put(def.PHASE_IMPLEMENT, implement)
            put(def.PHASE_AUDIT, audit)
            if (consumer != def.PHASE_VALIDATE && consumer != def.PHASE_BUILD) {
              put(def.PHASE_VALIDATE, validate)
            }
            if (consumer == def.PHASE_COMMIT_PUSH) {
              put(def.PHASE_WRITE_HISTORY, writeHistory)
            }
          },
        )
      val envelope =
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            consumerPhaseId = consumer
            declarations = phaseDeclarations
            resolvedUpstream = resolvedUpstreamOutputs
            resolvedCheckpoint = checkpoint
          },
        )
      val projection =
        envelope.projections.first {
          it.projectionName == "validation_request" ||
            it.projectionName == "boundary_candidates" ||
            it.projectionName == "commit_request"
        }
      val fieldName =
        when (projection.projectionName) {
          "commit_request" -> "path_inventory"
          else -> "changed_paths"
        }
      return assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
        projection.fields.single { it.name == fieldName }.value,
      ).items
    }

    assertEquals(expectedPaths, changedPathsFrom(def.PHASE_VALIDATE))
    assertEquals(expectedPaths, changedPathsFrom(def.PHASE_BUILD))
    assertEquals(expectedPaths, changedPathsFrom(def.PHASE_WRITE_HISTORY))
    assertEquals(expectedPaths, changedPathsFrom(def.PHASE_COMMIT_PUSH))
  }

  @Test
  fun `stuffed value JSON in implement and plan does not leak into typed finalization projection fields`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val stuffedPlan = """{"validation_strategy":["./gradlew check"],"tasks":[{"test_obligations":["t1"]}]}"""
    val stuffedImplement =
      """{"completed_task_ids":["task-x"],"tests_added":["t.kt"],"tests_updated":[],"deviations":["d"]}"""
    val plan = proseOutput(def.PHASE_PLAN, stuffedPlan)
    val implement = proseOutput(def.PHASE_IMPLEMENT, stuffedImplement)
    val audit = proseOutput(def.PHASE_AUDIT, "audit satisfied")
    val validate = recordedPhaseOutput(def.PHASE_VALIDATE, HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD)
    val commitPush = proseOutput(def.PHASE_COMMIT_PUSH, "pushed feat at abc")
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("tree-1", workingTreeOwnedPaths = listOf("src/Real.kt"))

    val validateEnvelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          consumerPhaseId = def.PHASE_VALIDATE
          declarations =
            FeatureTaskRuntimePhaseWorkflowQueries
              .phaseDeclaration(def.PHASE_VALIDATE, FeatureTaskRuntimeFeatureSize.MEDIUM)
              .projectionDeclarations
          resolvedUpstream =
            FeatureTaskRuntimeResolvedUpstreamOutputs(
              mapOf(def.PHASE_PLAN to plan, def.PHASE_AUDIT to audit),
            )
          resolvedCheckpoint = checkpoint
        },
      )
    val validationRequest = validateEnvelope.projections.single { it.projectionName == "validation_request" }
    assertEquals(
      listOf("changed_paths", "repository_checkpoint"),
      validationRequest.fields.map { it.name },
    )
    assertTrue(validationRequest.fields.none { it.name == "validation_strategy" || it.name == "required_checks" })

    assertPrRequestOmitsStuffedImplementFields(implement, validate, commitPush, checkpoint)
  }
}

class FeatureTaskRuntimeHandoffProjectionValidatorContractTest {
  @Test
  fun `build_receipt contract id is registered for handoff projection parsing`() {
    assertEquals(
      "feature_task_runtime.build_receipt",
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
    )
  }

  @Test
  fun `validation_receipt carries only the quality check finish signal`() {
    val expected = listOf("value")
    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    ).forEach { consumer ->
      val receipt =
        FeatureTaskRuntimePhaseWorkflowQueries
          .phaseDeclaration(consumer, FeatureTaskRuntimeFeatureSize.MEDIUM)
          .projectionDeclarations
          .single {
            it.projectionContractId ==
              FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT
          }
      assertEquals(expected, receipt.declaredFieldNames)
    }
  }

  @Test
  fun `write_history without the validate step rejects settled validate output`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY
    val unselected = setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    val declaration =
      FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationWithoutSteps(
        consumer,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
        unselected,
      )
    val upstream =
      FeatureTaskRuntimeResolvedUpstreamOutputs(
        mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
            FeatureTaskRuntimePhaseOutput(
              phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              iteration = 1,
              payload = """{"produced_outputs":{}}""",
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
            FeatureTaskRuntimePhaseOutput(
              phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
              iteration = 1,
              payload = """{"produced_outputs":{}}""",
            ),
        ),
      )
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            consumerPhaseId = consumer
            declarations = declaration.projectionDeclarations
            resolvedUpstream = upstream
            unselectedStepIds = unselected
          },
        )
      }
    assertTrue(error.message.orEmpty().contains("did not select step 'validate'"), error.message)
  }

  @Test
  fun `a non-required missing source is omitted rather than rejected`() {
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          declarations = listOf(handoffProjectionDeclaration { required = false })
          resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap())
        },
      )

    assertTrue(envelope.projections.isEmpty())
  }

  @Test
  fun `a duplicate projection name is rejected`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            declarations = listOf(handoffProjectionDeclaration(), handoffProjectionDeclaration())
          },
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.DUPLICATE_PROJECTION_NAME, error.failureKind)
  }

  @Test
  fun `a declaration for another consumer phase is rejected as malformed`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            declarations = listOf(handoffProjectionDeclaration { consumerPhaseId = "audit" })
          },
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD, error.failureKind)
  }

  @Test
  fun `an unsupported projection contract version is rejected`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            declarations = listOf(handoffProjectionDeclaration { contractVersion = "9.9" })
          },
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.UNSUPPORTED_CONTRACT_VERSION, error.failureKind)
    assertContains(error.message.orEmpty(), "9.9")
  }

  @Test
  fun `a field outside the declared shape is rejected as undeclared`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            declarations =
              listOf(
                handoffProjectionDeclaration { declaredFieldNames = listOf("some_other_field") },
              )
          },
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.UNDECLARED_FIELD, error.failureKind)
  }

  @Test
  fun `must_match refreshes instead of rejecting repository movement`() {
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          declarations =
            listOf(
              handoffProjectionDeclaration {
                checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH
              },
            )
          resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc")
          expectedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-def")
        },
      )
    assertEquals("head-abc", envelope.repositoryCheckpoint?.fingerprint)
  }

  @Test
  fun `must_match does not require a recorded checkpoint`() {
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          declarations =
            listOf(
              handoffProjectionDeclaration {
                checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH
              },
            )
          resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc")
        },
      )
    assertEquals("head-abc", envelope.repositoryCheckpoint?.fingerprint)
  }

  @Test
  fun `must_match accepts identical runtime checkpoints`() {
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("head-abc")
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          declarations =
            listOf(
              handoffProjectionDeclaration {
                checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH
              },
            )
          resolvedCheckpoint = checkpoint
          expectedCheckpoint = checkpoint
        },
      )
    assertEquals("head-abc", envelope.repositoryCheckpoint?.fingerprint)
  }

  @Test
  fun `refresh_from_repository requires a freshly resolved checkpoint`() {
    val missing =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            declarations =
              listOf(
                handoffProjectionDeclaration {
                  checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY
                },
              )
          },
        )
      }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION, missing.failureKind)

    val refreshed =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          declarations =
            listOf(
              handoffProjectionDeclaration {
                checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY
              },
            )
          resolvedCheckpoint =
            FeatureTaskRuntimeRepositoryCheckpoint(
              fingerprint = "head-abc",
              baseRef = "main",
              headRef = "feat/x",
              workingTreeOwnedPaths = listOf("src/Main.kt"),
            )
        },
      )
    assertEquals(listOf("src/Main.kt"), refreshed.repositoryCheckpoint?.workingTreeOwnedPaths)
  }

  @Test
  fun `an unauthorized private-evidence reference is rejected as an invalid compact reference`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            declarations =
              listOf(
                handoffProjectionDeclaration {
                  inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT
                  allowsPrivateArtifactReference = false
                },
              )
          },
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE, error.failureKind)
  }

  @Test
  fun `an authorized private-evidence reference replaces inline content with a deterministic locator`() {
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          declarations =
            listOf(
              handoffProjectionDeclaration {
                inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT
                allowsPrivateArtifactReference = true
              },
            )
        },
      )

    val value = envelope.projections.single().fields.single().value
    val reference = assertIs<FeatureTaskRuntimeHandoffProjectionValue.CompactReference>(value)
    assertEquals(FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT, reference.kind)
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValidator.privateEvidenceReference(HANDOFF_VALIDATOR_TEST_PRODUCER, 1),
      reference.value,
    )
    assertTrue(reference.kind.runtimeResolvable, "a private-artifact reference must be runtime-resolvable")
    assertFalse(reference.value.contains("""{"plan":"""), "the reference must not inline the private body")
  }

  @Test
  fun `a private-evidence locator mislabelled as another reference kind is still gated`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            declarations =
              listOf(
                handoffProjectionDeclaration {
                  inlineAlternative = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_PATH
                  allowsPrivateArtifactReference = false
                },
              )
          },
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE, error.failureKind)
    assertContains(error.message.orEmpty(), "private evidence artifact")
  }

  @Test
  fun `preplan prose handoff rejects whitespace-only value`() {
    val consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
    val declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(consumer)
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        FeatureTaskRuntimeHandoffProjectionValidator.validate(
          handoffProjectionValidatorInputs {
            consumerPhaseId = consumer
            declarations = listOf(declaration)
            resolvedUpstream =
              FeatureTaskRuntimeResolvedUpstreamOutputs(
                mapOf(
                  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
                    proseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN, "   "),
                ),
              )
          },
        )
      }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD, error.failureKind)
    assertContains(error.message.orEmpty(), "non-blank prose")
  }
}

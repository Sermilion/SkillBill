package skillbill.error.featuretask

import skillbill.error.core.ShellContentContractException

class UnknownPhaseStepError(
  val stepId: String,
) : ShellContentContractException("Phase step '$stepId' does not belong to any phase slot.")

class DuplicatePhaseStrategyError(
  val slot: String,
  val strategyId: String,
) : ShellContentContractException("Phase slot '$slot' registers strategy '$strategyId' more than once.")

class PhaseStrategyStepOutsideSlotError(
  val slot: String,
  val strategyId: String,
  val stepId: String,
) : ShellContentContractException(
    "Phase strategy '$strategyId' for slot '$slot' declares step '$stepId' outside that slot.",
  )

class UnknownPhaseStrategyError(
  val slot: String,
  val strategyId: String,
) : ShellContentContractException("Phase slot '$slot' has no strategy '$strategyId'.")

class InvalidSkeletonDefinitionError(
  val definitionId: String,
  val slots: List<String>,
) : ShellContentContractException(
    "Skeleton definition '$definitionId' must list distinct phase slots in canonical order, was $slots.",
  )

class UnknownSkeletonDefinitionError(
  val definitionId: String,
  val knownIds: List<String>,
) : ShellContentContractException(
    "Unknown skeleton definition '$definitionId'; expected one of ${knownIds.joinToString(", ")}.",
  )

class InMemorySkeletonDefinitionRequiredError(
  val definitionId: String,
) : ShellContentContractException(
    "Skeleton definition '$definitionId' runs over durable workflow state; a phase run drives only " +
      "in-memory definitions.",
  )

class InMemoryPhaseRunUnsupportedError(
  val operation: String,
) : ShellContentContractException(
    "An in-memory phase run keeps no durable state and cannot $operation; " +
      "run the full feature-task workflow instead.",
  )

class UnknownPhaseReviewTargetError(
  val target: String,
) : ShellContentContractException(
    "Review target '$target' does not name a commit in this repository; expected HEAD, uncommitted, or a commit " +
      "sha, branch, or tag.",
  )

class PhaseStrategySelectionSlotMismatchError(
  val definitionId: String,
  val slot: String,
) : ShellContentContractException(
    "Phase strategy selection for skeleton definition '$definitionId' must bind exactly its slots; " +
      "slot '$slot' is unbound or outside the definition.",
  )

class UnknownQualityGateSelectionError(
  val value: String,
  val allowedValues: List<String>,
) : ShellContentContractException(
    "Unknown quality-gate selection '$value'; expected one of ${allowedValues.joinToString(", ")}.",
  )

class UnregisteredPhaseStrategySelectionError(
  val slot: String,
  val strategyId: String,
) : ShellContentContractException(
    "Phase strategy selection for slot '$slot' names unregistered strategy '$strategyId'.",
  )

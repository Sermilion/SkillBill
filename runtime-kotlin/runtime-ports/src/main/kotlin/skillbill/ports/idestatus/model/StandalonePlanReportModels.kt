package skillbill.ports.idestatus.model

data class StandalonePlanReport(
  val featureName: String,
  val parentSpecPath: String,
  val subtasks: List<StandalonePlanSubtask>,
)

data class StandalonePlanSubtask(
  val id: Int,
  val name: String,
  val specPath: String,
  val dependsOn: List<Int>,
)

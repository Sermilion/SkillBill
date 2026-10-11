package skillbill.ports.idestatus

interface WorkPayloadValidator {
  fun validateBoard(
    payload: String,
    sourceLabel: String,
  )

  fun validateDetail(
    payload: String,
    sourceLabel: String,
  )
}

package skillbill.ports.projectstore.model

sealed interface SpecPathLocation {
  val relative: String

  data class Store(override val relative: String) : SpecPathLocation

  data class Legacy(override val relative: String) : SpecPathLocation
}

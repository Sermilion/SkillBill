package skillbill.ports.blockincident.model

sealed interface ProductReportDeliveryOwnership {
  /** The caller owns the report operation. */
  interface Held : ProductReportDeliveryOwnership {
    /** Releases the OS lock and local reservation. */
    fun close()
  }

  data object Contended : ProductReportDeliveryOwnership

  data class Failed(val cause: Throwable) : ProductReportDeliveryOwnership
}

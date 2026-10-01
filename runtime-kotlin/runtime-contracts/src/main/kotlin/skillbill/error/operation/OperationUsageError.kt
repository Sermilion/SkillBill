package skillbill.error.operation

import skillbill.error.core.ShellContentContractException

open class OperationUsageError(
  message: String,
) : ShellContentContractException(message)

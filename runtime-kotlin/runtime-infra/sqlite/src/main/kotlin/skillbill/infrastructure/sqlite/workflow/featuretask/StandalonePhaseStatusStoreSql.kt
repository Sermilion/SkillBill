package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.infrastructure.sqlite.worklist.fractionalSecondsSql

internal fun instantAtOrBeforeSql(columnName: String): String =
  "(unixepoch($columnName) < unixepoch((SELECT value FROM cutoff)) OR " +
    "(unixepoch($columnName) = unixepoch((SELECT value FROM cutoff)) AND " +
    "${fractionalSecondsSql(columnName)} <= ${fractionalSecondsSql("(SELECT value FROM cutoff)")}))"

internal fun instantAtOrAfterBoundSql(
  columnName: String,
  boundName: String,
): String =
  "(unixepoch($columnName) > unixepoch((SELECT $boundName FROM bounds)) OR " +
    "(unixepoch($columnName) = unixepoch((SELECT $boundName FROM bounds)) AND " +
    "${fractionalSecondsSql(columnName)} >= ${fractionalSecondsSql("(SELECT $boundName FROM bounds)")}))"

internal fun instantAfterBoundSql(
  columnName: String,
  boundName: String,
): String =
  "(unixepoch($columnName) > unixepoch((SELECT $boundName FROM bounds)) OR " +
    "(unixepoch($columnName) = unixepoch((SELECT $boundName FROM bounds)) AND " +
    "${fractionalSecondsSql(columnName)} > ${fractionalSecondsSql("(SELECT $boundName FROM bounds)")}))"

internal fun instantAtOrBeforeBoundSql(
  columnName: String,
  boundName: String,
): String =
  "(unixepoch($columnName) < unixepoch((SELECT $boundName FROM bounds)) OR " +
    "(unixepoch($columnName) = unixepoch((SELECT $boundName FROM bounds)) AND " +
    "${fractionalSecondsSql(columnName)} <= ${fractionalSecondsSql("(SELECT $boundName FROM bounds)")}))"

internal fun incrementDecimal(value: String): String {
  val digits = value.toCharArray()
  var carry = 1
  for (index in digits.lastIndex downTo 0) {
    if (carry == 0) break
    if (digits[index] == '9') {
      digits[index] = '0'
    } else {
      digits[index] = (digits[index].code + 1).toChar()
      carry = 0
    }
  }
  return if (carry == 1) "1${digits.concatToString()}" else String(digits)
}

internal fun validLaunchArgument(
  value: Any?,
  maxLength: Int,
): Boolean = value is String && value.length <= maxLength

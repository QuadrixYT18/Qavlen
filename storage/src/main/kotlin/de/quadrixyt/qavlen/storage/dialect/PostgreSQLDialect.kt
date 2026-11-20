package de.quadrixyt.qavlen.storage.dialect

/** PostgreSQL. */
public object PostgreSQLDialect : DatabaseDialect {
    override fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    override fun targetType(sourceJdbcType: Int, declaredSize: Int, decimalDigits: Int): String =
        genericType(sourceJdbcType, declaredSize, decimalDigits, "BYTEA", "BOOLEAN")
}

package de.quadrixyt.qavlen.storage.dialect

/** SQLite. Booleans are stored as integers. */
public object SQLiteDialect : DatabaseDialect {
    override fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    override fun targetType(sourceJdbcType: Int, declaredSize: Int, decimalDigits: Int): String =
        genericType(sourceJdbcType, declaredSize, decimalDigits, "BLOB", "INTEGER")
}

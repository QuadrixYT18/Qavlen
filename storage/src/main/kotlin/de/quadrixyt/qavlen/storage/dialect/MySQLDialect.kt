package de.quadrixyt.qavlen.storage.dialect

/** MariaDB and MySQL. */
public object MySQLDialect : DatabaseDialect {
    override fun quote(identifier: String): String = "`${identifier.replace("`", "``")}`"

    override fun targetType(sourceJdbcType: Int, declaredSize: Int, decimalDigits: Int): String =
        genericType(sourceJdbcType, declaredSize, decimalDigits, "LONGBLOB", "BOOLEAN")
}

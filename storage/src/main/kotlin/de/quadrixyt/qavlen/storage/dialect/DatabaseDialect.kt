package de.quadrixyt.qavlen.storage.dialect

import de.quadrixyt.qavlen.storage.DatabaseType
import java.sql.Types

/** SQL differences between database systems: identifier quoting and column types. */
public interface DatabaseDialect {
    /** Quotes [identifier] and escapes quote characters inside it. */
    public fun quote(identifier: String): String

    /** The column type for a source column with the given JDBC type, size and decimal digits. */
    public fun targetType(sourceJdbcType: Int, declaredSize: Int, decimalDigits: Int): String
}

/** The dialect of [type]. */
public fun dialect(type: DatabaseType): DatabaseDialect = when (type) {
    DatabaseType.SQLITE -> SQLiteDialect
    DatabaseType.MARIADB -> MySQLDialect
    DatabaseType.POSTGRESQL -> PostgreSQLDialect
}

internal fun genericType(jdbc: Int, size: Int, digits: Int, binary: String, boolean: String): String = when (jdbc) {
    Types.BOOLEAN, Types.BIT -> boolean
    Types.TINYINT, Types.SMALLINT -> "SMALLINT"
    Types.INTEGER -> "INTEGER"
    Types.BIGINT -> "BIGINT"
    Types.FLOAT, Types.REAL -> "REAL"
    Types.DOUBLE -> "DOUBLE PRECISION"
    Types.NUMERIC, Types.DECIMAL -> if (size in 1..65 && digits in 0..size) "DECIMAL($size,$digits)" else "DECIMAL"
    Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> if (size in 1..16000) "VARCHAR($size)" else "TEXT"
    Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> "TEXT"
    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> binary
    Types.DATE -> "DATE"
    Types.TIME, Types.TIME_WITH_TIMEZONE -> "TIME"
    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP"
    else -> error("Unsupported JDBC source column type: $jdbc")
}

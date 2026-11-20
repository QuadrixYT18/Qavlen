package de.quadrixyt.qavlen.storage.migration

import java.sql.Connection
import java.sql.DatabaseMetaData

public data class ColumnInfo(
    public val name: String,
    public val jdbcType: Int,
    public val size: Int,
    public val digits: Int,
    public val nullable: Boolean,
    public val autoIncrement: Boolean,
)

public data class TableInfo(public val name: String, public val columns: List<ColumnInfo>, public val primaryKey: List<String>)

/** Reads tables, columns and primary keys through JDBC metadata, skipping system tables. */
public class SchemaInspector {
    public fun tables(connection: Connection): List<TableInfo> {
        val meta = connection.metaData
        val catalog = connection.catalog
        val schema = connection.schema
        val names = mutableListOf<String>()
        meta.getTables(catalog, schema, "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) {
                val name = rs.getString("TABLE_NAME")
                if (!name.startsWith("sqlite_") && !name.startsWith("pg_") && !name.startsWith("sys")) names += name
            }
        }
        return names.distinct().sorted().map { name ->
            val columns = mutableListOf<ColumnInfo>()
            meta.getColumns(catalog, schema, name, "%").use { rs ->
                while (rs.next()) {
                    columns += ColumnInfo(
                        name = rs.getString("COLUMN_NAME"),
                        jdbcType = rs.getInt("DATA_TYPE"),
                        size = rs.getInt("COLUMN_SIZE"),
                        digits = rs.getInt("DECIMAL_DIGITS"),
                        nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        autoIncrement = rs.getString("IS_AUTOINCREMENT").equals("YES", ignoreCase = true),
                    )
                }
            }
            val primary = sortedMapOf<Short, String>()
            meta.getPrimaryKeys(catalog, schema, name).use { rs ->
                while (rs.next()) primary[rs.getShort("KEY_SEQ")] = rs.getString("COLUMN_NAME")
            }
            TableInfo(name, columns, primary.values.toList())
        }
    }
}

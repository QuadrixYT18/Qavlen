package de.quadrixyt.qavlen.storage.migration

import de.quadrixyt.qavlen.storage.dialect.DatabaseDialect
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Copies all tables with their columns, primary keys and rows from one database into an empty one, in a single
 * transaction on the target. Row counts are compared before the commit.
 *
 * Tables with auto-increment columns, foreign keys or secondary and unique indexes are rejected before anything is written.
 */
public class DatabaseTransfer(
    private val inspector: SchemaInspector = SchemaInspector(),
    private val validator: MigrationValidator = MigrationValidator(),
) {
    public fun transfer(
        source: Connection,
        target: Connection,
        sourceDialect: DatabaseDialect,
        targetDialect: DatabaseDialect,
        batchSize: Int = 500,
    ) {
        require(batchSize > 0) { "batchSize must be positive" }
        require(source !== target) { "Source and target connection must differ" }
        require(!target.isReadOnly) { "Target connection is read-only" }
        val tables = inspector.tables(source)
        require(inspector.tables(target).isEmpty()) { "Target schema is not empty. Refusing to overwrite existing data." }
        checkSupported(source, tables)
        val originalAutoCommit = target.autoCommit
        try {
            target.autoCommit = false
            for (table in tables) copyTable(source, target, table, sourceDialect, targetDialect, batchSize)
            validator.checkCounts(source, target, tables, sourceDialect, targetDialect)
            target.commit()
        } catch (e: Exception) {
            try {
                target.rollback()
            } catch (rollback: Exception) {
                e.addSuppressed(rollback)
            }
            throw e
        } finally {
            target.autoCommit = originalAutoCommit
        }
    }

    private fun checkSupported(source: Connection, tables: List<TableInfo>) {
        val md = source.metaData
        for (table in tables) {
            require(table.columns.isNotEmpty()) { "Table ${table.name} has no columns" }
            require(table.columns.none { it.autoIncrement }) { "Auto-increment not yet supported: ${table.name}" }
            md.getImportedKeys(source.catalog, source.schema, table.name).use { rs ->
                require(!rs.next()) { "Foreign keys not yet supported: ${table.name}" }
            }
            md.getIndexInfo(source.catalog, source.schema, table.name, false, false).use { rs ->
                while (rs.next()) {
                    val index = rs.getString("INDEX_NAME") ?: continue
                    val isPkIndex = table.primaryKey.isNotEmpty() && (index.equals("PRIMARY", true) || index.contains("pkey", true))
                    require(isPkIndex) { "Secondary/unique index not yet supported: ${table.name}: $index" }
                }
            }
        }
    }

    private fun copyTable(
        source: Connection,
        target: Connection,
        table: TableInfo,
        sourceDialect: DatabaseDialect,
        targetDialect: DatabaseDialect,
        batchSize: Int,
    ) {
        val tableName = targetDialect.quote(table.name)
        val defs = table.columns.map { col ->
            val type = targetDialect.targetType(col.jdbcType, col.size, col.digits)
            "${targetDialect.quote(col.name)} $type${if (col.nullable) "" else " NOT NULL"}"
        }
        val primaryKey = table.primaryKey.joinToString(", ") { targetDialect.quote(it) }
        val pk = if (table.primaryKey.isEmpty()) "" else ", PRIMARY KEY ($primaryKey)"
        target.createStatement().use { it.executeUpdate("CREATE TABLE $tableName (${defs.joinToString(", ")}$pk)") }
        val sourceName = sourceDialect.quote(table.name)
        val columns = table.columns.joinToString(", ") { targetDialect.quote(it.name) }
        val placeholders = table.columns.joinToString(", ") { "?" }
        source.createStatement().use { st ->
            st.fetchSize = batchSize
            st.executeQuery("SELECT * FROM $sourceName").use { rows ->
                target.prepareStatement("INSERT INTO $tableName ($columns) VALUES ($placeholders)").use { insert ->
                    copyRows(rows, insert, table.columns.size, batchSize)
                }
            }
        }
    }

    private fun copyRows(rows: ResultSet, insert: PreparedStatement, columnCount: Int, batchSize: Int) {
        var batch = 0
        while (rows.next()) {
            for (index in 1..columnCount) insert.setObject(index, rows.getObject(index))
            insert.addBatch()
            batch++
            if (batch == batchSize) {
                insert.executeBatch()
                batch = 0
            }
        }
        if (batch != 0) insert.executeBatch()
    }
}

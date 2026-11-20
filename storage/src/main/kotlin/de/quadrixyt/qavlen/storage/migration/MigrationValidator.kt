package de.quadrixyt.qavlen.storage.migration

import de.quadrixyt.qavlen.storage.dialect.DatabaseDialect
import java.sql.Connection

/** Checks that every table has the same number of rows in source and target. */
public class MigrationValidator {
    public fun checkCounts(
        source: Connection,
        target: Connection,
        tables: List<TableInfo>,
        sourceDialect: DatabaseDialect,
        targetDialect: DatabaseDialect,
    ) {
        tables.forEach { t ->
            val s = count(source, sourceDialect.quote(t.name))
            val d = count(target, targetDialect.quote(t.name))
            require(s == d) { "Row count mismatch in ${t.name}: source=$s, target=$d" }
        }
    }

    private fun count(connection: Connection, name: String): Long = connection.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM $name").use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }
}

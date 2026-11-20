package de.quadrixyt.qavlen.config

/**
 * Combines edits made to a configuration file with changes a program holds in memory, so saving never discards
 * what someone wrote into the file while the program was running.
 */
public object ConfigMerge {
    /** The merged values and the paths where the file and the memory changed the same value differently. */
    public class Result internal constructor(
        public val values: Map<String, Any?>,
        public val conflicts: List<String>,
    )

    /**
     * Three-way merge of configuration trees. [base] is the file as last read or written, [file] its content now and
     * [memory] the program's current values. A value only one side changed takes that side's version, including
     * added and removed keys; sections are merged key by key. When both sides changed the same value differently,
     * the file wins and the path is listed in [Result.conflicts].
     */
    @JvmStatic
    public fun merge(base: Map<String, Any?>, file: Map<String, Any?>, memory: Map<String, Any?>): Result {
        val conflicts = mutableListOf<String>()
        return Result(merge(base, file, memory, "", conflicts), conflicts)
    }

    @Suppress("UNCHECKED_CAST")
    private fun merge(
        base: Map<String, Any?>?,
        file: Map<String, Any?>,
        memory: Map<String, Any?>,
        prefix: String,
        conflicts: MutableList<String>,
    ): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        // The file's key order wins; keys the program added follow.
        for (key in file.keys + memory.keys) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val before = if (base != null && base.containsKey(key)) base[key] else Absent
            val edited = if (file.containsKey(key)) file[key] else Absent
            val changed = if (memory.containsKey(key)) memory[key] else Absent
            val value = if (areSections(before, edited, changed)) {
                merge(before as? Map<String, Any?>, edited as Map<String, Any?>, changed as Map<String, Any?>, path, conflicts)
            } else {
                when {
                    changed == before -> edited
                    edited == before || edited == changed -> changed
                    else -> edited.also { conflicts += path }
                }
            }
            if (value !== Absent) result[key] = value
        }
        return result
    }

    /** `true` when both sides hold a section and the base held one too or nothing, so the sections merge key by key. */
    private fun areSections(before: Any?, edited: Any?, changed: Any?): Boolean =
        edited is Map<*, *> && changed is Map<*, *> && (before is Map<*, *> || before === Absent)

    /** Marks a key that does not exist on one side, which differs from a stored `null`. */
    private object Absent
}

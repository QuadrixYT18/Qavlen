package de.quadrixyt.qavlen.core

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Owns resources and closes them once, in reverse registration order. */
public class ResourceScope : AutoCloseable {
    private val closed = AtomicBoolean()
    private val lock = Any()
    private val resources = ArrayDeque<AutoCloseable>()

    /** Registers a resource, or closes it immediately if this scope is already closed. */
    public fun <T : AutoCloseable> own(resource: T): T {
        val closeImmediately = synchronized(lock) {
            if (closed.get()) {
                true
            } else {
                resources.addFirst(resource)
                false
            }
        }

        if (closeImmediately) resource.close()
        return resource
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val toClose = synchronized(lock) {
            buildList {
                while (resources.isNotEmpty()) add(resources.removeFirst())
            }
        }

        var failure: Throwable? = null
        for (resource in toClose) {
            try {
                resource.close()
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        failure?.let { throw it }
    }
}

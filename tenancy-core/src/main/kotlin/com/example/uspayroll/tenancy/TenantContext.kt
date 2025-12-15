package com.example.uspayroll.tenancy

/**
 * Thread-local tenant context.
 *
 * In the db-per-employer model, the tenant key is the employer external ID.
 * All DB access must execute with a tenant set.
 */
object TenantContext {
    private val holder: ThreadLocal<String?> = ThreadLocal.withInitial { null }

    fun get(): String? = holder.get()

    fun require(): String = requireNotNull(get()) { "No tenant set in TenantContext" }

    fun set(tenant: String) {
        holder.set(tenant)
    }

    fun clear() {
        holder.remove()
    }

    inline fun <T> withTenant(tenant: String, block: () -> T): T {
        val previous = get()
        set(tenant)
        return try {
            block()
        } finally {
            if (previous == null) {
                clear()
            } else {
                set(previous)
            }
        }
    }
}

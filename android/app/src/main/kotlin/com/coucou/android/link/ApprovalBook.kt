package com.coucou.android.link

/**
 * Pending approvals as the phone sees them. A decision can only be sent for a request that is
 * still pending and younger than [Protocol.APPROVAL_TTL_MS]; each request is decided once.
 */
class ApprovalBook(private val clockMs: () -> Long) {
    private val pending = LinkedHashMap<String, ApprovalRequest>()

    @Synchronized fun add(r: ApprovalRequest) { pending[r.fingerprint] = r }

    @Synchronized fun resolve(fingerprint: String): ApprovalRequest? = pending.remove(fingerprint)

    /** Still-valid requests, oldest first. Expired ones are dropped as a side effect. */
    @Synchronized fun active(): List<ApprovalRequest> {
        val now = clockMs()
        pending.values.removeAll { now - it.createdAtMs >= Protocol.APPROVAL_TTL_MS }
        return pending.values.toList()
    }

    /** Takes the request out for deciding; null if unknown, already decided or expired. */
    @Synchronized fun claim(fingerprint: String): ApprovalRequest? {
        active()
        return pending.remove(fingerprint)
    }

    @Synchronized fun clear() = pending.clear()
}

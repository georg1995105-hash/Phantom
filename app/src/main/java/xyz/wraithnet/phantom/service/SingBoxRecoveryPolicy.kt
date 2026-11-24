package xyz.wraithnet.phantom.service

/** Bounded fail-closed recovery; the running Xray TUN is never replaced. */
internal object SingBoxRecoveryPolicy {
    const val MAX_ATTEMPTS = 3

    fun canAttempt(
        running: Boolean,
        requested: Boolean,
        revoked: Boolean,
        sameEpoch: Boolean,
        coreAndTunAlive: Boolean,
        workerAbsent: Boolean,
        hasProfileAndPinnedPort: Boolean,
        hasPhysicalNetwork: Boolean,
        attempts: Int,
    ): Boolean = running && requested && !revoked && sameEpoch &&
        coreAndTunAlive && workerAbsent && hasProfileAndPinnedPort &&
        hasPhysicalNetwork && attempts in 0 until MAX_ATTEMPTS

    fun delayBeforeAttemptMs(completedAttempts: Int): Long = when (completedAttempts) {
        0 -> 500L
        1 -> 1_500L
        else -> 4_000L
    }
}

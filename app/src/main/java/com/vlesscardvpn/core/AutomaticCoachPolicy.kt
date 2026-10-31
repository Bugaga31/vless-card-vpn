package com.vlesscardvpn.core

/** Sampling advice never initiates search, reconnects VPN or rewrites user settings. */
object AutomaticCoachPolicy {
    const val SAMPLE_MS = 60_000L
    const val INVENTORY_MS = 180_000L
    fun active(uiVisible: Boolean, vpnActive: Boolean, searching: Boolean) = uiVisible || vpnActive || searching
    fun inventoryDue(visible: Boolean, now: Long, last: Long) = visible && (last < 0 || now < last || now - last >= INVENTORY_MS)
    /** First failed native cohort is followed by bounded mask trials, not six native batches. */
    fun nativeCohortSize(selected: Int) = when (selected) { 1, 3, 5, 10 -> selected; else -> 5 }
}

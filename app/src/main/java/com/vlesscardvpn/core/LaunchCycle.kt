package com.vlesscardvpn.core

/** One silent presentation per cold process launch; background/rotation never rearms it. */
class LaunchCycle {
    data class Frame(val serial: Long = 0, val visible: Boolean = false)
    var frame = Frame(); private set
    private var pending = true
    fun enter(enabled: Boolean): Frame {
        if (pending) {
            pending = false
            frame = Frame(frame.serial + 1, enabled)
        }
        return frame
    }
    fun leave(changingConfiguration: Boolean) { if (!changingConfiguration) frame = frame.copy(visible = false) }
    fun dismiss(serial: Long): Frame {
        if (serial == frame.serial) frame = frame.copy(visible = false)
        return frame
    }
}

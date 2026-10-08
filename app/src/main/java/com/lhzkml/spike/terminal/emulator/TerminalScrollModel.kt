/*
 * Ported from Noxs (https://github.com/) — Apache License 2.0.
 * Original: Noxs terminal-emulator / terminal-view modules.
 * Adapted for Proot-Pi (package rename + local constants); behaviour unchanged.
 */
package com.lhzkml.spike.terminal.emulator

enum class TerminalScrollMode { NORMAL, SMART, HISTORY }

class TerminalScrollModel {

    var mode: TerminalScrollMode = TerminalScrollMode.SMART

    /** When true, an anchored (offset 0) viewport follows newly pushed rows. */
    var followLiveOutput: Boolean = true

    /** Whether the "↓ N new lines" affordance may be displayed at all. */
    var showNewOutputIndicator: Boolean = true

    /**
     * SMART extension: keep following output while a process is producing it.
     * When false, output only pulls the viewport while it is anchored AND the
     * user has not scrolled away during the burst.
     */
    var autoFollowWhileRunning: Boolean = true

    /** Rows that arrived while the user was away from the live bottom. */
    var newLinesBehind: Int = 0
        private set

    private var following = true

    val isFollowing: Boolean get() = following

    /** Called once when the view attaches to a session/buffer. */
    fun reset() {
        newLinesBehind = 0
        following = true
    }

    /**
     * The user scrolled. [userAtBottom] true means the viewport reached the
     * live bottom (offset 0) after this scroll.
     */
    fun onUserScroll(userAtBottom: Boolean) {
        if (userAtBottom) {
            following = true
            newLinesBehind = 0
        } else {
            following = false
        }
    }

    /**
     * [pushedRows] new rows entered the scrollback. [userAtBottom] reports the
     * viewport anchor BEFORE the view applies its follow policy, and
     * [sessionHasProcess] tells whether an active process may still produce
     * output (drives autoFollowWhileRunning in SMART mode).
     *
     * Returns true when the viewport should keep following the live bottom.
     */
    fun onOutputPushed(
        pushedRows: Int,
        userAtBottom: Boolean,
        sessionHasProcess: Boolean = true,
        usingAltScreen: Boolean = false
    ): Boolean {
        if (pushedRows <= 0) return following
        // Alternate screens (vim, top, htop, less…) always stay live: their
        // full-screen redraws are meaningless to offset-scroll through history.
        if (usingAltScreen) {
            newLinesBehind = 0
            following = true
            return true
        }
        when (mode) {
            TerminalScrollMode.NORMAL -> {
                // Standard behavior: an anchored viewport rides along with new
                // rows, a displaced viewport holds its position silently and
                // no new-output counter is kept.
                following = userAtBottom
                newLinesBehind = 0
                return following
            }
            TerminalScrollMode.SMART -> {
                val keepFollowing = if (userAtBottom) {
                    following && followLiveOutput &&
                        (!sessionHasProcess || autoFollowWhileRunning)
                } else false
                following = keepFollowing
                if (!following) newLinesBehind += pushedRows else newLinesBehind = 0
                return following
            }
            TerminalScrollMode.HISTORY -> {
                following = userAtBottom && followLiveOutput
                if (!following) newLinesBehind += pushedRows else newLinesBehind = 0
                return following
            }
        }
    }

    /** True when the mode wants the new-output affordance rendered now. */
    fun shouldShowIndicator(userAtBottom: Boolean): Boolean = when (mode) {
        TerminalScrollMode.NORMAL -> false
        TerminalScrollMode.SMART ->
            showNewOutputIndicator && !userAtBottom && newLinesBehind > 0
        TerminalScrollMode.HISTORY ->
            showNewOutputIndicator && !userAtBottom
    }

    /** Indicator text without the arrow glyph; view adds style. */
    fun indicatorLabel(userAtBottom: Boolean, viewportOffset: Int): String? = when (mode) {
        TerminalScrollMode.NORMAL -> null
        TerminalScrollMode.SMART ->
            if (shouldShowIndicator(userAtBottom) && newLinesBehind > 0) "$newLinesBehind new lines" else null
        TerminalScrollMode.HISTORY ->
            if (shouldShowIndicator(userAtBottom)) {
                val behind = viewportOffset
                if (behind > 0) "$behind lines behind" else null
            } else null
    }
}

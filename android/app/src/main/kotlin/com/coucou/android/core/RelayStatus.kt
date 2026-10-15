package com.coucou.android.core

import com.coucou.android.link.RelayIssue
import com.coucou.android.link.Route

/** What the "Away from home Wi-Fi" card says. One kind per sentence; the screen maps it to a string. */
enum class RelayLineKind { NO_RELAY_IN_PAIRING, SWITCHED_OFF, DIRECT, VIA_RELAY, WAITING, ACCESS_REFUSED, ROOM_TAKEN, RATE_LIMITED, UNREACHABLE, COMPUTER_AWAY }

object RelayStatus {
    /**
     * Whether Settings shows "Away from home Wi-Fi" at all. A phone that is paired with a relay always sees it (it holds the switch).
     * One that is not sees it only once the computer has said it offers the relay (`relay` in the welcome), because only then
     * "scan the new code" is something it can do; for a computer without the relay the section would be a dead end.
     */
    fun sectionVisible(paired: Boolean, hasRelay: Boolean, computerOffersRelay: Boolean): Boolean =
        paired && (hasRelay || computerOffersRelay)

    fun kind(hasRelay: Boolean, useRelay: Boolean, connected: Boolean, route: Route?, issue: RelayIssue): RelayLineKind = when {
        !hasRelay -> RelayLineKind.NO_RELAY_IN_PAIRING
        !useRelay -> RelayLineKind.SWITCHED_OFF
        connected && route == Route.RELAY -> RelayLineKind.VIA_RELAY
        connected -> RelayLineKind.DIRECT
        else -> when (issue) {
            RelayIssue.ACCESS_REFUSED -> RelayLineKind.ACCESS_REFUSED
            RelayIssue.ROOM_TAKEN -> RelayLineKind.ROOM_TAKEN
            RelayIssue.RATE_LIMITED -> RelayLineKind.RATE_LIMITED
            RelayIssue.UNREACHABLE -> RelayLineKind.UNREACHABLE
            RelayIssue.COMPUTER_AWAY -> RelayLineKind.COMPUTER_AWAY
            RelayIssue.NONE -> RelayLineKind.WAITING
        }
    }
}

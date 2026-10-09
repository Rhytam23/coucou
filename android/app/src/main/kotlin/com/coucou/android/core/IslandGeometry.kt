package com.coucou.android.core

import kotlin.math.max
import kotlin.math.min

/** A rectangle in pixels (the display cutout's bounding box at the top of the screen). */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val width: Int get() = right - left
}

/** Where the island sits and how big it is, in pixels. */
data class IslandBox(
    /** Horizontal offset of the island's centre from the screen's centre (negative = left). */
    val centerOffsetX: Int,
    /** Space at the top that the camera cut-out or status bar takes: content starts below it. */
    val topInset: Int,
    /** Width of the island when it is hidden in the notch. */
    val notchWidth: Int,
    val workingW: Int, val workingH: Int,
    val cardW: Int, val cardH: Int,
    val approvalW: Int, val approvalH: Int,
    val cornerSmall: Int, val cornerLarge: Int,
)

/**
 * The island is centred on the camera cut-out (a centred waterdrop on the Galaxy A12s), its black
 * merges with the hole, and its content starts under it. With no cut-out it hangs from the
 * status bar, centred on the screen. Sizes follow the PC (working strip, card, request card).
 */
object IslandGeometry {
    const val NOTCH_DP = 184 // NOTCH_W on the PC
    const val MARGIN_DP = 12
    const val MAX_W_DP = 360

    fun place(screenW: Int, density: Float, cutoutTop: PxRect?, statusBarH: Int): IslandBox {
        fun dp(v: Int) = (v * density).toInt()
        val maxW = min(screenW - 2 * dp(MARGIN_DP), dp(MAX_W_DP))
        val cut = cutoutTop?.takeIf { it.width > 0 && it.bottom > 0 }
        val top = max(cut?.bottom ?: statusBarH, 0)
        // Keep the island on screen even when the cut-out is off-centre.
        val rawOffset = cut?.let { it.centerX - screenW / 2 } ?: 0
        val room = max(screenW / 2 - maxW / 2, 0)
        val offset = rawOffset.coerceIn(-room, room)
        val notch = min(max((cut?.width ?: 0) + dp(24), dp(NOTCH_DP)), maxW)
        return IslandBox(
            centerOffsetX = offset, topInset = top, notchWidth = notch,
            workingW = min(dp(300), maxW), workingH = top + dp(52),
            cardW = maxW, cardH = top + dp(96),
            approvalW = maxW, approvalH = top + dp(196),
            cornerSmall = dp(14), cornerLarge = dp(22),
        )
    }

    /** Size for a kind of content. */
    fun sizeFor(box: IslandBox, kind: IslandSpec.Kind): Pair<Int, Int> = when (kind) {
        IslandSpec.Kind.WORKING -> box.workingW to box.workingH
        IslandSpec.Kind.APPROVAL -> box.approvalW to box.approvalH
        else -> box.cardW to box.cardH
    }
}

package me.bsdnme.tvbox.ui.player

import kotlin.math.roundToLong

/** Map a full-screen swipe to a useful part of the video, without making long films too sensitive. */
internal fun swipeSeekTarget(startMs: Long, deltaPx: Float, widthPx: Int, durationMs: Long): Long {
    if (widthPx <= 0 || durationMs <= 0) return startMs.coerceAtLeast(0)
    val rangeMs = (durationMs / 3).coerceIn(60_000L, 600_000L)
    val deltaMs = (deltaPx / widthPx * rangeMs).roundToLong()
    return (startMs + deltaMs).coerceIn(0, durationMs)
}

internal fun remoteSeekTarget(currentMs: Long, direction: Int, repeatCount: Int, durationMs: Long): Long {
    val stepMs = when {
        repeatCount >= 20 -> 30_000L
        repeatCount >= 8 -> 20_000L
        else -> 10_000L
    }
    return (currentMs + direction * stepMs).coerceIn(0, durationMs.coerceAtLeast(0))
}

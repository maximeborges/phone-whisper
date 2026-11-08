package com.kafkasl.phonewhisper

import android.content.SharedPreferences

/** Overlay bubble appearance settings (size + idle colour). */
object BubbleStyle {
    const val PREF_SIZE = "bubble_size_pct"
    const val PREF_COLOR = "bubble_color"

    const val MIN_PCT = 100
    const val MAX_PCT = 300
    const val STEP_PCT = 25
    const val DEFAULT_COLOR = 0x1C1C1E

    /** 16 preset RGB colours (white mic icon stays readable on all of them). */
    val PALETTE: List<Int> = listOf(
        0x1C1C1E, // graphite
        0x334155, // slate
        0x1E3A8A, // navy
        0x2563EB, // blue
        0x0EA5E9, // sky
        0x0891B2, // cyan
        0x0D9488, // teal
        0x16A34A, // green
        0x4D7C0F, // olive
        0xB45309, // amber
        0xEA580C, // orange
        0xDB2777, // pink
        0xC026D3, // fuchsia
        0x7C3AED, // violet
        0x4F46E5, // indigo
        0x78350F, // brown
    )

    fun sizePct(p: SharedPreferences): Int =
        p.getInt(PREF_SIZE, MIN_PCT).coerceIn(MIN_PCT, MAX_PCT)

    fun color(p: SharedPreferences): Int =
        p.getInt(PREF_COLOR, DEFAULT_COLOR) and 0xFFFFFF

    /** Idle fill colour with the bubble's translucency applied. */
    fun idleArgb(p: SharedPreferences): Int = (0xDD shl 24) or color(p)
}

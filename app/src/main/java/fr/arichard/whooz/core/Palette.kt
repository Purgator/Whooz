package fr.arichard.whooz.core

/** Player colors and the arena background. Pure Kotlin, shared by both modes and the tests. */
object Palette {
    /** Arena background; must match `@color/arena`. */
    const val BACKGROUND: Int = 0xFF0B0E14.toInt()

    /** Sixteen opaque neon colors; the slot index of a finger or face is its color index. */
    val COLORS: IntArray = intArrayOf(
        0xFFFF3B6B.toInt(), 0xFF3BE8FF.toInt(), 0xFFB6FF3B.toInt(), 0xFFFFB13B.toInt(),
        0xFFC13BFF.toInt(), 0xFF3BFFA8.toInt(), 0xFFFF6B3B.toInt(), 0xFF3B8CFF.toInt(),
        0xFFFFE63B.toInt(), 0xFFFF3BE1.toInt(), 0xFF7CFF3B.toInt(), 0xFFFF8C3B.toInt(),
        0xFFFFFFFF.toInt(), 0xFF19E3D1.toInt(), 0xFF8C6BFF.toInt(), 0xFFFF5CE1.toInt(),
    )

    const val SIZE: Int = 16

    /** Color of a slot; slots wrap around so an out-of-range index never throws. */
    fun of(slot: Int): Int = COLORS[((slot % SIZE) + SIZE) % SIZE]

    /** Team colors are the first k palette entries. */
    fun team(team: Int): Int = of(team)

    /** [color] with its alpha replaced by [alpha] (0..255, clamped). */
    fun withAlpha(color: Int, alpha: Int): Int {
        val a = if (alpha < 0) 0 else if (alpha > 255) 255 else alpha
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** [color] with its alpha scaled by [fraction] (0..1, clamped). */
    fun withAlpha(color: Int, fraction: Float): Int =
        withAlpha(color, (Easing.clamp01(fraction) * 255f + 0.5f).toInt())
}

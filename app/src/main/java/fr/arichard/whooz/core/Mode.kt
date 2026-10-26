package fr.arichard.whooz.core

/**
 * What a round decides. Serialized to a short preference string ("winner:1", "order",
 * "teams:2"); anything unknown reads back as the default [Winner] of one.
 */
sealed class Mode {
    /** Keep [count] players, eliminate the others one by one. */
    data class Winner(val count: Int) : Mode() {
        init { require(count >= 1) { "count must be >= 1" } }
    }

    /** Give every player a play order 1..N. */
    data object Order : Mode()

    /** Split the players into [count] teams of sizes differing by at most one. */
    data class Teams(val count: Int) : Mode() {
        init { require(count >= 2) { "count must be >= 2" } }
    }

    fun serialize(): String = when (this) {
        is Winner -> "winner:$count"
        Order -> "order"
        is Teams -> "teams:$count"
    }

    companion object {
        val DEFAULT: Mode = Winner(1)
        const val MAX_WINNERS = 3
        const val MAX_TEAMS = 3

        /** The sub-modes offered by the finger screen, in pill order. */
        val FINGER_MODES: List<Mode> = listOf(Winner(1), Winner(2), Winner(3), Order, Teams(2), Teams(3))

        /** The sub-modes offered by the camera screen. */
        val CAMERA_MODES: List<Mode> = listOf(Winner(1), Winner(2))

        fun parse(text: String?): Mode {
            if (text == null) return DEFAULT
            val parts = text.trim().split(':')
            val n = parts.getOrNull(1)?.toIntOrNull()
            return when (parts[0]) {
                "winner" -> if (n != null && n in 1..MAX_WINNERS) Winner(n) else DEFAULT
                "order" -> Order
                "teams" -> if (n != null && n in 2..MAX_TEAMS) Teams(n) else DEFAULT
                else -> DEFAULT
            }
        }
    }
}

/**
 * The outcome of one round, drawn once when the participant set is frozen. Arrays are
 * indexed by slot (0 until [Palette.SIZE]); entries of non-participants are -1 / false.
 */
class Assignment(
    /** True for every slot that wins (Winner mode); all participants for Order/Teams. */
    val winner: BooleanArray,
    /** Play order 1..N per slot (Order mode), else -1. */
    val order: IntArray,
    /** Team index 0..k-1 per slot (Teams mode), else -1. */
    val team: IntArray,
    /** Slots eliminated, in the order they vanish (Winner mode); empty otherwise. */
    val eliminationOrder: IntArray,
)

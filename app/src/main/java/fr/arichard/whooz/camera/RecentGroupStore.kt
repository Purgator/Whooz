package fr.arichard.whooz.camera

/** Bounded roll-call history that lives only as long as its owning activity. */
class RecentGroupStore<T>(
    private val maxGroups: Int = DEFAULT_MAX_GROUPS,
    private val onDiscard: (T) -> Unit = {},
) {

    data class Group<T>(val items: List<T>)

    private val history = ArrayList<List<T>>(maxGroups)

    fun groups(): List<Group<T>> = history.map(::Group)

    fun saveGroup(items: List<T>) {
        if (items.isEmpty()) return
        history.add(0, items.toList())
        while (history.size > maxGroups) history.removeAt(history.lastIndex).forEach(onDiscard)
    }

    companion object {
        private const val DEFAULT_MAX_GROUPS = 5
    }
}
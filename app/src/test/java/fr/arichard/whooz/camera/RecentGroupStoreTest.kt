package fr.arichard.whooz.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class RecentGroupStoreTest {

    @Test
    fun `given six groups when saving them then only the five newest remain in memory`() {
        val store = RecentGroupStore<String>()

        repeat(6) { index -> store.saveGroup(listOf("group-$index")) }

        assertEquals(
            listOf("group-5", "group-4", "group-3", "group-2", "group-1"),
            store.groups().map { it.items.single() },
        )
    }

    @Test
    fun `given a mutable group when saving it then the history keeps its own list`() {
        val store = RecentGroupStore<String>()
        val items = mutableListOf("Alice", "Bob")

        store.saveGroup(items)
        items.clear()

        assertEquals(listOf("Alice", "Bob"), store.groups().single().items)
        assertNotSame(items, store.groups().single().items)
    }
}
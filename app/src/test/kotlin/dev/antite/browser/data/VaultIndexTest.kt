package dev.antite.browser.data

import dev.antite.browser.data.MediaVault.Folder
import dev.antite.browser.data.MediaVault.Item
import dev.antite.keys.Cipher256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultIndexTest {
    private val items = listOf(
        Item(1, "a.jpg", "image/jpeg", 10, 100, "https://example.com/a", null),
        Item(2, "b.mp4", "video/mp4", 20, 200, "", 7),
    )
    private val folders = listOf(Folder(7, "trip"), Folder(8, "day 1", parent = 7), Folder(9, "night", parent = 8))

    @Test
    fun roundTripKeepsItemsFoldersAndKeys() {
        val k1 = ByteArray(Cipher256.KEY) { 1 }
        val k2 = ByteArray(Cipher256.KEY) { 2 }
        val back = VaultIndex.decode(VaultIndex.encode(items, folders, mapOf(1L to k1, 2L to k2, 99L to k1)))
        assertEquals(items, back.items)
        assertEquals(folders, back.folders)
        assertEquals(setOf(1L, 2L), back.keys.keys)
        assertArrayEquals(k1, back.keys[1])
        assertArrayEquals(k2, back.keys[2])
    }

    @Test
    fun keysStayOutOfTheJson() {
        val key = ByteArray(Cipher256.KEY) { 0x41 }
        val bytes = VaultIndex.encode(items.take(1), emptyList(), mapOf(1L to key))
        val jsonLength = java.nio.ByteBuffer.wrap(bytes).int
        val json = String(bytes, 4, jsonLength)
        assertFalse(json.contains("AAAAAAAA"))
        assertEquals(4 + jsonLength + 8 + Cipher256.KEY, bytes.size)
    }

    @Test
    fun deletingAFolderLiftsItsContentsOneLevel() {
        val (fs, its) = VaultIndex.deleteFolder(folders, items, folders[0])
        assertEquals(listOf(Folder(8, "day 1", parent = null), Folder(9, "night", parent = 8)), fs)
        assertEquals(null, its.first { it.id == 2L }.folder)
    }

    @Test
    fun aFolderCannotGoIntoItselfOrItsOwnFolders() {
        assertFalse(VaultIndex.canMove(folders, folders[0], 7))
        assertFalse(VaultIndex.canMove(folders, folders[0], 9))
        assertTrue(VaultIndex.canMove(folders, folders[2], 7))
        assertTrue(VaultIndex.canMove(folders, folders[1], null))
    }

    @Test
    fun aBrokenLoopInTheTreeDoesNotHang() {
        val loop = listOf(Folder(1, "x", parent = 2), Folder(2, "y", parent = 1), Folder(3, "z"))
        assertTrue(VaultIndex.canMove(loop, loop[2], 1))
        assertEquals(64, VaultIndex.path(loop, 1).size)
    }

    @Test
    fun pathGoesFromTheTopDown() {
        assertEquals(listOf(7L, 8L, 9L), VaultIndex.path(folders, 9).map { it.id })
        assertEquals(emptyList<Folder>(), VaultIndex.path(folders, null))
    }
}

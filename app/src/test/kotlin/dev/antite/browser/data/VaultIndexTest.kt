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
        Item(VaultFileId(1), "a.jpg", "image/jpeg", 10, 100, "https://example.com/a", null),
        Item(VaultFileId(2), "b.mp4", "video/mp4", 20, 200, "", d(7)),
    )
    private val folders = listOf(Folder(d(7), "trip"), Folder(d(8), "day 1", parent = d(7)), Folder(d(9), "night", parent = d(8)))

    private fun d(n: Long) = VaultFolderId(n)

    @Test
    fun roundTripKeepsItemsFoldersAndKeys() {
        val k1 = ByteArray(Cipher256.KEY) { 1 }
        val k2 = ByteArray(Cipher256.KEY) { 2 }
        val back = VaultIndex.decode(VaultIndex.encode(items, folders, mapOf(VaultFileId(1) to k1, VaultFileId(2) to k2, VaultFileId(99) to k1)))
        assertEquals(items, back.items)
        assertEquals(folders, back.folders)
        assertEquals(setOf(VaultFileId(1), VaultFileId(2)), back.keys.keys)
        assertArrayEquals(k1, back.keys[VaultFileId(1)])
        assertArrayEquals(k2, back.keys[VaultFileId(2)])
    }

    @Test
    fun boxesStayOutOfTheJson() {
        val box = ByteArray(1 + 24 + Cipher256.KEY + Cipher256.TAG) { 0x41 }
        val bytes = VaultIndex.encode(items.take(1), emptyList(), mapOf(VaultFileId(1) to box))
        val jsonLength = java.nio.ByteBuffer.wrap(bytes).int
        val json = String(bytes, 4, jsonLength)
        assertFalse(json.contains("AAAAAAAA"))
        assertEquals(4 + jsonLength + 8 + 2 + box.size, bytes.size)
        val back = VaultIndex.decode(bytes)
        assertFalse(back.raw)
        assertTrue(box.contentEquals(back.keys.getValue(VaultFileId(1))))
    }

    // An index of version 2 held the keys themselves, 64 bytes each after the JSON, and no version in it.
    @Test
    fun aVersion2IndexGivesItsRawKeys() {
        val json = "{\"items\":[{\"id\":1,\"name\":\"a\",\"mime\":\"image/png\",\"size\":1,\"time\":1,\"source\":\"\"}],\"folders\":[]}".toByteArray()
        val key = ByteArray(Cipher256.KEY) { 7 }
        val bytes = java.nio.ByteBuffer.allocate(4 + json.size + 8 + Cipher256.KEY).putInt(json.size).put(json).putLong(1).put(key).array()
        val i = VaultIndex.decode(bytes)
        assertTrue(i.raw)
        assertTrue(key.contentEquals(i.keys.getValue(VaultFileId(1))))
        assertEquals(1, i.items.size)
    }

    @Test
    fun deletingAFolderLiftsItsContentsOneLevel() {
        val (fs, its) = VaultIndex.deleteFolder(folders, items, folders[0])
        assertEquals(listOf(Folder(d(8), "day 1", parent = null), Folder(d(9), "night", parent = d(8))), fs)
        assertEquals(null, its.first { it.id == VaultFileId(2) }.folder)
    }

    @Test
    fun aFolderCannotGoIntoItselfOrItsOwnFolders() {
        assertFalse(VaultIndex.canMove(folders, folders[0], d(7)))
        assertFalse(VaultIndex.canMove(folders, folders[0], d(9)))
        assertTrue(VaultIndex.canMove(folders, folders[2], d(7)))
        assertTrue(VaultIndex.canMove(folders, folders[1], null))
    }

    @Test
    fun aBrokenLoopInTheTreeDoesNotHang() {
        val loop = listOf(Folder(d(1), "x", parent = d(2)), Folder(d(2), "y", parent = d(1)), Folder(d(3), "z"))
        assertTrue(VaultIndex.canMove(loop, loop[2], d(1)))
        assertEquals(64, VaultIndex.path(loop, d(1)).size)
    }

    @Test
    fun pathGoesFromTheTopDown() {
        assertEquals(listOf(d(7), d(8), d(9)), VaultIndex.path(folders, d(9)).map { it.id })
        assertEquals(emptyList<Folder>(), VaultIndex.path(folders, null))
    }
}

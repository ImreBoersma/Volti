package nl.imreboersma.volti.drive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFilesTest {
    @Test
    fun parsesDrivesAnswer() {
        val page = parsePage(
            """
            {
              "kind": "drive#fileList",
              "nextPageToken": "next",
              "files": [
                {"id": "f1", "name": "Clarinet 2.pdf", "mimeType": "application/pdf", "md5Checksum": "abc", "size": "12345"},
                {"id": "d1", "name": "Clarinets", "mimeType": "application/vnd.google-apps.folder", "resourceKey": "0-key"}
              ]
            }
            """,
        )
        assertEquals("next", page.nextPageToken)
        assertEquals(DriveFile("f1", "Clarinet 2.pdf", PDF_TYPE, "abc", "12345"), page.files[0])
        assertEquals("0-key", page.files[1].resourceKey)
        assertNull(page.files[1].md5Checksum)
    }

    @Test
    fun anEmptyFolderHasNoFilesField() {
        val page = parsePage("""{"kind": "drive#fileList"}""")
        assertTrue(page.files.isEmpty())
        assertNull(page.nextPageToken)
    }

    private fun pdf(id: String) = DriveFile(id, "$id.pdf", PDF_TYPE)
    private fun folder(id: String) = DriveFile(id, id, FOLDER_TYPE)

    /** A fake Drive: children per folder id, served [pageSize] at a time. */
    private class FakeDrive(val children: Map<String, List<DriveFile>>, val pageSize: Int = 2) {
        val calls = mutableListOf<String>()
        fun list(folder: DriveFolderLink, token: String?): DrivePage {
            calls += folder.id
            val all = children[folder.id].orEmpty()
            val start = token?.toInt() ?: 0
            val end = minOf(start + pageSize, all.size)
            return DrivePage(all.subList(start, end), if (end < all.size) end.toString() else null)
        }
    }

    @Test
    fun walksSubfoldersAndPagesAndSkipsOtherTypes() = runBlocking {
        val drive = FakeDrive(
            mapOf(
                "root" to listOf(pdf("score"), folder("clarinets"), DriveFile("doc", "Notes", "application/vnd.google-apps.document")),
                "clarinets" to listOf(pdf("cl1"), pdf("cl2"), pdf("cl3"), folder("old")),
                "old" to listOf(pdf("cl1-2019")),
            ),
        )
        val listing = walkDriveFolder(DriveFolderLink("root")) { f, t -> drive.list(f, t) }
        assertEquals(
            listOf("score" to listOf(), "cl1" to listOf("clarinets"), "cl2" to listOf("clarinets"),
                "cl3" to listOf("clarinets"), "cl1-2019" to listOf("clarinets", "old")),
            listing.pdfs.map { it.file.id to it.folders },
        )
        assertEquals(2, listing.folders)
        assertFalse(listing.stopped)
    }

    @Test
    fun aFolderInTwoPlacesIsWalkedOnce() = runBlocking {
        val drive = FakeDrive(
            mapOf(
                "root" to listOf(folder("a"), folder("b")),
                "a" to listOf(folder("shared")),
                "b" to listOf(folder("shared"), folder("root")), // And a loop back to the top.
                "shared" to listOf(pdf("p")),
            ),
        )
        val listing = walkDriveFolder(DriveFolderLink("root")) { f, t -> drive.list(f, t) }
        assertEquals(listOf("p"), listing.pdfs.map { it.file.id })
        assertEquals(1, drive.calls.count { it == "shared" })
        assertEquals(1, drive.calls.count { it == "root" })
    }

    @Test
    fun stopsAtTheLimitWithTheTopLevelsComplete() = runBlocking {
        val drive = FakeDrive(
            mapOf(
                "root" to listOf(pdf("1"), pdf("2"), folder("deep")),
                "deep" to listOf(pdf("3"), pdf("4"), pdf("5")),
            ),
        )
        val listing = walkDriveFolder(DriveFolderLink("root"), limit = 4) { f, t -> drive.list(f, t) }
        assertTrue(listing.stopped)
        assertEquals(listOf("1", "2", "3"), listing.pdfs.map { it.file.id })
    }

    @Test
    fun anIdThatIsNotADriveIdIsLeftOut() = runBlocking {
        val asked = mutableListOf<String>()
        val listing = walkDriveFolder(DriveFolderLink("rootFolder01")) { f, _ ->
            asked += f.id
            if (f.id == "rootFolder01") {
                DrivePage(
                    listOf(
                        DriveFile("x' or 'a' in parents or '", "Sneaky", FOLDER_TYPE),
                        DriveFile("bad/id", "Bad.pdf", PDF_TYPE),
                        DriveFile("goodFile0001", "Good.pdf", PDF_TYPE),
                    ),
                )
            } else DrivePage()
        }
        assertEquals(listOf("rootFolder01"), asked)
        assertEquals(listOf("goodFile0001"), listing.pdfs.map { it.file.id })
    }

    @Test
    fun theResourceKeyOfASubfolderIsUsedToListIt() = runBlocking {
        val asked = mutableListOf<DriveFolderLink>()
        walkDriveFolder(DriveFolderLink("root")) { f, _ ->
            asked += f
            if (f.id == "root") DrivePage(listOf(DriveFile("sub", "Sub", FOLDER_TYPE, resourceKey = "0-k"))) else DrivePage()
        }
        assertEquals(DriveFolderLink("sub", "0-k"), asked[1])
    }
}

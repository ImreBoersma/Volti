package nl.imreboersma.volti.drive

import nl.imreboersma.volti.db.EnsembleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class LocalCopyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v1 = "1".repeat(32)
    private val v2 = "2".repeat(32)
    private val v3 = "3".repeat(32)

    private fun found(id: String, md5: String?, name: String = "$id.pdf", folders: List<String> = emptyList()) =
        DriveEntry(DriveFile(id, name, PDF_TYPE, md5, "100"), folders)

    private fun row(id: String, md5: String, local: String? = md5, seen: String? = null, removed: Boolean = false) =
        EnsembleFile(1, id, null, "$id.pdf", emptyList(), md5, 100, local, seen, removed)

    @Test
    fun aNewPdfIsAddedNotYetDownloaded() {
        val rows = reconcile(1, emptyList(), listOf(found("a", v1, folders = listOf("Hoorn"))))
        assertEquals(listOf(EnsembleFile(1, "a", null, "a.pdf", listOf("Hoorn"), v1, 100)), rows)
    }

    @Test
    fun aChangedPdfKeepsWhatIsOnTheTabletAndWhatISaw() {
        val rows = reconcile(1, listOf(row("a", v1, seen = v1)), listOf(found("a", v2)))
        assertEquals(v2, rows.single().md5)
        assertEquals(v1, rows.single().localMd5) // Until the new one is downloaded.
        assertEquals(v1, rows.single().seenMd5)
        assertFalse(rows.single().changed) // Not yet: the new version isn't here.
        assertTrue(rows.single().copy(localMd5 = v2).changed)
    }

    @Test
    fun renamesAndMovesFollowDrive() {
        val rows = reconcile(1, listOf(row("a", v1)), listOf(found("a", v1, "Hoorn 1.pdf", listOf("Hoorn"))))
        assertEquals("Hoorn 1.pdf", rows.single().name)
        assertEquals(listOf("Hoorn"), rows.single().folders)
        assertEquals(v1, rows.single().localMd5)
    }

    @Test
    fun goneIsDroppedUnlessIOpenedIt() {
        val rows = reconcile(1, listOf(row("never", v1), row("played", v2, seen = v2)), emptyList())
        assertEquals(listOf("played"), rows.map { it.fileId })
        assertTrue(rows.single().removed)
        assertFalse(reconcile(1, rows, listOf(found("played", v2))).single().removed) // And it can come back.
    }

    @Test
    fun anIncompleteWalkDropsNothingItDidntReach() {
        val rows = reconcile(1, listOf(row("a", v1), row("b", v1)), listOf(found("a", v1)), complete = false)
        assertEquals(listOf("a" to false, "b" to false), rows.map { it.fileId to it.removed })
    }

    @Test
    fun noOrAMalformedChecksumIsLeftOutAndDuplicatesCountOnce() {
        val rows = reconcile(
            1,
            emptyList(),
            listOf(found("a", null), found("b", "../../evil"), found("c", v1, folders = listOf("x")), found("c", v1, folders = listOf("y"))),
        )
        assertEquals(listOf("c" to listOf("x")), rows.map { it.fileId to it.folders })
    }

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private val pdf = "%PDF-1.7\n...the rest of a score...".toByteArray()

    @Test
    fun aGoodPdfIsStored() {
        val store = PdfStore(tmp.newFolder("drive"))
        store.store(md5(pdf), pdf.size.toLong(), pdf.inputStream())
        assertTrue(store.has(md5(pdf)))
        assertEquals(pdf.toList(), store.fileFor(md5(pdf)).readBytes().toList())
    }

    private fun rejected(bytes: ByteArray, md5: String, limit: Long = MAX_PDF_BYTES): String {
        val target = File(tmp.root, "target.pdf")
        try {
            copyChecked(bytes.inputStream(), target, md5, limit)
            fail("accepted")
        } catch (e: DownloadRejected) {
            assertFalse("the partial file is gone", target.exists())
            return e.message!!
        }
        error("unreachable")
    }

    @Test
    fun whatIsntWhatDriveSaidIsRejected() {
        assertEquals("Checksum doesn't match", rejected(pdf, v1))
        val html = "<html>Sign in</html>".toByteArray()
        assertEquals("Not a PDF", rejected(html, md5(html)))
        assertEquals("Larger than 0 MB", rejected(pdf, md5(pdf), limit = 10))
    }

    @Test
    fun theHeaderMayComeAfterSomeJunk() {
        val padded = ByteArray(500) { ' '.code.toByte() } + pdf
        copyChecked(padded.inputStream(), File(tmp.root, "ok.pdf"), md5(padded))
    }

    @Test
    fun cleanUpKeepsOnlyWhatIsInUse() {
        val dir = tmp.newFolder("drive")
        val store = PdfStore(dir)
        listOf(v1, v2).forEach { store.fileFor(it).writeText("x") }
        File(dir, "download-abc.tmp").writeText("half")
        store.keepOnly(setOf(v2))
        assertEquals(listOf("$v2.pdf"), dir.list()!!.toList())
    }
}

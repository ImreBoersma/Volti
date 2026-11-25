package nl.imreboersma.volti.data

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Against the real storage provider, with the folder grant this debug build holds on the tablet. A grant can
 * only come from the system picker, so where there is none (CI) these tests are skipped, unless the run asks
 * for a grant: on the tablet, run with -Pandroid.testInstrumentationRunnerArguments.requireGrant=true, and a
 * missing grant fails instead of passing quietly.
 */
@RunWith(AndroidJUnit4::class)
class GrantedTreeTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private lateinit var tree: Uri

    @Before
    fun setUp() {
        val grant = resolver.persistedUriPermissions.firstOrNull { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        if (InstrumentationRegistry.getArguments().getString("requireGrant") == "true") {
            assertNotNull("no folder grant: pick a folder in the debug build first", grant)
        }
        assumeTrue("no folder grant on this device", grant != null)
        tree = grant!!.uri
    }

    @Test
    fun theGrantedFolderHasItsRealName() {
        val name = folderName(resolver, tree)
        assertNotNull(name)
        assertNotEquals("the raw document id is not a name", DocumentsContract.getTreeDocumentId(tree), name)
    }

    @Test
    fun subfoldersOfTheGrantCanBeListed() {
        val sub = listFolder(resolver, tree)!!.filterIsInstance<Subfolder>().firstOrNull()
        assumeTrue("the granted folder has no subfolders", sub != null)
        assertNotNull(listFolder(resolver, tree, sub!!.documentId))
    }

    @Test
    fun nothingOutsideTheGrantIsReachable() {
        val top = DocumentsContract.getTreeDocumentId(tree)
        val volume = top.substringBefore(':') + ":"
        val sibling = top + "2" // a folder whose name starts with the granted folder's name
        // These always exist, so the provider must refuse them as outside the tree.
        for (id in listOf(top.substringBeforeLast('/', missingDelimiterValue = volume), volume, "$top/..").filter { it != top }) {
            assertThrows("listing $id should be refused", SecurityException::class.java) { listFolder(resolver, tree, id) }
        }
        // These may not exist on a device: refused, or reported missing, both mean nothing is listed. On the
        // tablet the sibling exists (Documents/VoltiTest2), so there it is the refusal that is tested.
        for (id in listOf(volume + "Download", sibling, "$top/../${sibling.substringAfterLast('/')}")) {
            val e = assertThrows("listing $id should fail", Exception::class.java) { listFolder(resolver, tree, id) }
            assertTrue(
                "listing $id failed for the wrong reason: $e",
                e is SecurityException || (e is IllegalArgumentException && "Missing file" in e.message.orEmpty()),
            )
        }
    }
}

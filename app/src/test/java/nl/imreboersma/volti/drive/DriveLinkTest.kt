package nl.imreboersma.volti.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveLinkTest {
    private val id = "1FKt5mZ7Ht-HehtD8IIAvBO9X3b3DLeOA"

    @Test
    fun sharedFolderLinks() {
        assertEquals(DriveFolderLink(id), parseFolderLink("https://drive.google.com/drive/folders/$id?usp=sharing"))
        assertEquals(DriveFolderLink(id), parseFolderLink("https://drive.google.com/drive/folders/$id"))
        assertEquals(DriveFolderLink(id), parseFolderLink("  https://drive.google.com/drive/u/1/folders/$id/  "))
        assertEquals(DriveFolderLink(id), parseFolderLink("https://drive.google.com/open?id=$id"))
        assertEquals(DriveFolderLink(id), parseFolderLink(id))
    }

    @Test
    fun theResourceKeyComesAlong() {
        assertEquals(
            DriveFolderLink(id, "0-abcDEF123_xyz"),
            parseFolderLink("https://drive.google.com/drive/folders/$id?resourcekey=0-abcDEF123_xyz&usp=sharing"),
        )
    }

    @Test
    fun anythingElseIsNotAFolder() {
        assertNull(parseFolderLink("https://drive.google.com/file/d/$id/view")) // A file, not a folder.
        assertNull(parseFolderLink("http://drive.google.com/drive/folders/$id")) // Not HTTPS.
        assertNull(parseFolderLink("https://evil.example/drive/folders/$id"))
        assertNull(parseFolderLink("https://drive.google.com.evil.example/drive/folders/$id"))
        assertNull(parseFolderLink("https://drive.google.com/drive/folders/"))
        assertNull(parseFolderLink("https://drive.google.com/drive/folders/abc' or name contains 'x"))
        assertNull(parseFolderLink("Harmonie"))
        assertNull(parseFolderLink(""))
    }
}

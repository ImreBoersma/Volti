package nl.imreboersma.volti.data

import android.database.MatrixCursor
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScoreListerCursorTest {
    @Test
    fun readsColumnsByNameAndSkipsIncompleteRows() {
        // Columns in a different order than the projection asks for.
        val cursor = MatrixCursor(
            arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID),
        ).apply {
            addRow(arrayOf("application/pdf", "Bolero.pdf", "1"))
            addRow(arrayOf("application/pdf", null, "2"))
            addRow(arrayOf("application/pdf", "No id.pdf", null))
            addRow(arrayOf(null, "Unknown type", "4"))
        }

        assertEquals(
            listOf(
                DocumentRow("1", "Bolero.pdf", "application/pdf"),
                DocumentRow("4", "Unknown type", null),
            ),
            cursor.use { it.toRows() },
        )
    }
}

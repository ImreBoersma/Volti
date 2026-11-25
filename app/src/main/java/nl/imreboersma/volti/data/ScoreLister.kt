package nl.imreboersma.volti.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.buildChildDocumentsUriUsingTree
import android.provider.DocumentsContract.buildDocumentUriUsingTree
import android.provider.DocumentsContract.getTreeDocumentId
import java.text.Collator

data class Score(val uri: Uri, val name: String)

internal data class DocumentRow(val id: String, val name: String, val mimeType: String?)

private const val PDF = "application/pdf"

/** PDFs only, by MIME type, sorted by name the way a person reads them. */
internal fun pdfRows(rows: List<DocumentRow>): List<DocumentRow> {
    val collator = Collator.getInstance()
    return rows.filter { it.mimeType == PDF }.sortedWith(compareBy(collator) { it.name })
}

/** Reads columns by name; skips rows without an id or a name instead of failing the whole list. */
internal fun Cursor.toRows(): List<DocumentRow> {
    val id = getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
    val name = getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
    val mime = getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
    return buildList {
        while (moveToNext()) {
            add(DocumentRow(getString(id) ?: continue, getString(name) ?: continue, getString(mime)))
        }
    }
}

/**
 * Lists the PDFs directly inside [tree] with one query, not one per file.
 * Returns null if the folder is gone; the provider returns no cursor then.
 * Throws SecurityException if the grant is gone. Blocking: call off the main thread.
 */
fun listScores(resolver: ContentResolver, tree: Uri): List<Score>? {
    val children = buildChildDocumentsUriUsingTree(tree, getTreeDocumentId(tree))
    val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    val rows = resolver.query(children, projection, null, null, null)?.use { it.toRows() } ?: return null
    return pdfRows(rows).map { Score(buildDocumentUriUsingTree(tree, it.id), it.name) }
}

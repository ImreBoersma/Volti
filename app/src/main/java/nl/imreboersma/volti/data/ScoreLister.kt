package nl.imreboersma.volti.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.buildChildDocumentsUriUsingTree
import android.provider.DocumentsContract.buildDocumentUriUsingTree
import android.provider.DocumentsContract.getTreeDocumentId
import java.text.Collator

/** Something the library lists: a score to open, or a folder to go into. */
sealed interface LibraryEntry {
    val name: String
}

data class Score(val uri: Uri, override val name: String) : LibraryEntry

/** A folder inside the granted tree, by its document id. */
data class Subfolder(val documentId: String, override val name: String) : LibraryEntry

internal data class DocumentRow(val id: String, val name: String, val mimeType: String?)

private const val PDF = "application/pdf"

/**
 * Compares names the way a person reads them: "Mars 2" before "Mars 10", case and accents as the
 * locale sorts them. Runs of ASCII digits compare by value, other runs by [Collator], and a number
 * comes before text. Every chunk is one kind or the other by how it was cut, never by a second test:
 * a fullwidth or Arabic-Indic digit is text, so the order stays consistent and sorting can't throw.
 */
internal class NaturalOrder(private val collator: Collator = Collator.getInstance()) : Comparator<String> {
    private val chunk = Regex("""[0-9]+|[^0-9]+""")

    override fun compare(a: String, b: String): Int {
        val left = chunk.findAll(a).map { it.value }.iterator()
        val right = chunk.findAll(b).map { it.value }.iterator()
        while (left.hasNext() && right.hasNext()) {
            val x = left.next()
            val y = right.next()
            val xNumber = x[0] in '0'..'9'
            val yNumber = y[0] in '0'..'9'
            val result = when {
                xNumber && yNumber -> compareNumbers(x, y)
                xNumber -> -1
                yNumber -> 1
                else -> collator.compare(x, y)
            }
            if (result != 0) return result
        }
        return left.hasNext().compareTo(right.hasNext())
    }

    /** By value, without parsing: no overflow on a 30-digit run. Leading zeros break ties ("07" after "7"). */
    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        return compareValuesBy(a, b, { it.length }, { it }).takeIf { it != 0 } ?: x.length.compareTo(y.length)
    }
}

/** Folders first, then PDFs (by MIME type), each in natural order. Everything else is left out. */
internal fun libraryRows(rows: List<DocumentRow>): List<DocumentRow> {
    val order = NaturalOrder()
    val (folders, files) = rows.partition { it.mimeType == Document.MIME_TYPE_DIR }
    return folders.sortedWith(compareBy(order) { it.name }) +
        files.filter { it.mimeType == PDF }.sortedWith(compareBy(order) { it.name })
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
 * Lists the subfolders and PDFs directly inside [folderId] (default: the top of [tree]) with one query,
 * not one per file. Returns null if the folder is gone; the provider returns no cursor then. Throws
 * SecurityException if the grant is gone, or if [folderId] is not inside [tree]: the provider checks
 * that, so nothing outside the granted folder is reachable. Blocking: call off the main thread.
 */
fun listFolder(resolver: ContentResolver, tree: Uri, folderId: String = getTreeDocumentId(tree)): List<LibraryEntry>? {
    val children = buildChildDocumentsUriUsingTree(tree, folderId)
    val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    val rows = resolver.query(children, projection, null, null, null)?.use { it.toRows() } ?: return null
    return libraryRows(rows).map { row ->
        if (row.mimeType == Document.MIME_TYPE_DIR) {
            Subfolder(row.id, row.name)
        } else {
            Score(buildDocumentUriUsingTree(tree, row.id), row.name)
        }
    }
}

/** The display name of the top of [tree], as the user knows it ("Bladmuziek"). Null if it can't be read. */
fun folderName(resolver: ContentResolver, tree: Uri): String? {
    val top = buildDocumentUriUsingTree(tree, getTreeDocumentId(tree))
    return resolver.query(top, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}

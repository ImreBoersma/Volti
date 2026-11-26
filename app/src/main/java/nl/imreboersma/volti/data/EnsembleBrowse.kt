package nl.imreboersma.volti.data

import nl.imreboersma.volti.db.EnsembleFile

/** What an ensemble's folder shows: its subfolders, then its PDFs. */
sealed interface EnsembleRow {
    data class Folder(val name: String) : EnsembleRow
    data class Pdf(val file: EnsembleFile) : EnsembleRow
}

/**
 * The rows at [path] (folder names from the ensemble's own folder down), the way the library lists a folder:
 * folders first, then PDFs, each in natural order. Folders only exist through the PDFs in them, so a folder
 * without PDFs anywhere below it isn't there.
 */
fun ensembleRows(files: List<EnsembleFile>, path: List<String>): List<EnsembleRow> {
    val order = NaturalOrder()
    val below = files.filter { it.folders.size >= path.size && it.folders.subList(0, path.size) == path }
    val folders = below.filter { it.folders.size > path.size }.map { it.folders[path.size] }.distinct()
    val pdfs = below.filter { it.folders.size == path.size }
    return folders.sortedWith(order).map { EnsembleRow.Folder(it) } +
        pdfs.sortedWith(compareBy(order) { it.name }).map { EnsembleRow.Pdf(it) }
}

/**
 * [path], or as much of it as still has PDFs below it: a sync can remove or rename the folder I was in, and then I
 * land in the nearest folder that is still there instead of an empty one.
 */
fun existingPath(files: List<EnsembleFile>, path: List<String>): List<String> {
    var n = path.size
    while (n > 0 && files.none { it.folders.size >= n && it.folders.subList(0, n) == path.subList(0, n) }) n--
    return path.subList(0, n)
}

/**
 * The PDFs in the whole ensemble that match [query], as the library searches: every word in the name or in a folder
 * on the way, case and accents aside. By folder, then name.
 */
fun searchEnsemble(files: List<EnsembleFile>, query: String): List<EnsembleFile> {
    val words = searchWords(query)
    return sortedByPlace(files.filter { matchesWords(words, (it.folders + it.name).map(::searchable)) })
}

/** By folder, then name, in natural order: how a list across folders reads. */
fun sortedByPlace(files: List<EnsembleFile>): List<EnsembleFile> {
    val order = NaturalOrder()
    return files.sortedWith(compareBy<EnsembleFile, String>(order) { it.folders.joinToString("\u0000") }.thenBy(order) { it.name })
}

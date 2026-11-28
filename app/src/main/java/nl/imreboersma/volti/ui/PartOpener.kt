package nl.imreboersma.volti.ui

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.drive.Syncer

/**
 * Opens an ensemble's part for a screen that lists parts: an ensemble's own, or my parts from every ensemble. Tells
 * [onProblem] why it didn't open, and null as a new open starts.
 */
class PartOpener(
    private val scope: CoroutineScope,
    private val db: VoltiDatabase,
    private val syncer: Syncer,
    private val onProblem: (DriveProblem?) -> Unit,
) {
    /**
     * Opens the newest version on the device, once it is recorded as the one I saw: from then on it is kept, also
     * while a later sync brings a newer one. One that isn't here is downloaded first, beside any other download: a
     * file that is here opens meanwhile. One still downloading when another opened opens nothing when it's done (the
     * screen isn't showing any more). The sync has stopped by the time the reader shows: none runs while reading.
     */
    fun open(file: EnsembleFile, onOpen: (uri: String, scoreKey: String) -> Unit) {
        val key = file.ensembleId to file.fileId
        latest = key
        if (key in fetching) return // A second tap on the same file waits for the first.
        onProblem(null)
        opening[key] = scope.launch {
            // Through the store's lock also when it is here, so a sync can't delete it between the tap and the reader.
            if (file.localMd5 == null) fetching += key
            val problem = try {
                syncer.fetch(file.ensembleId, file.fileId, open = true)
            } finally {
                fetching -= key
            }
            opening.remove(key)
            if (key != latest) return@launch // I tapped another meanwhile: that one opens, this one is here now.
            val here = db.files().all(file.ensembleId).firstOrNull { it.fileId == file.fileId }?.seenMd5
            when {
                problem != null -> onProblem(problem)
                here != null -> onOpen(Uri.fromFile(syncer.fileFor(here)).toString(), file.scoreKey)
            }
        }
    }

    /** Downloads pause while reading: one still going to open a file stops too (its copy is cleaned up). */
    fun stop() {
        opening.values.toList().forEach { it.cancel() }
        opening.clear()
        latest = null
    }

    private val fetching = mutableSetOf<Pair<Long, String>>()

    /** The file I tapped last: only it opens, also when another one I tapped finishes downloading first. */
    private var latest: Pair<Long, String>? = null

    /** Downloads to open a file: they stop with the screen, as the sync does. */
    private val opening = mutableMapOf<Pair<Long, String>, Job>()
}

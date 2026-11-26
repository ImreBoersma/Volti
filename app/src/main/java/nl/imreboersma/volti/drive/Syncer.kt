package nl.imreboersma.volti.drive

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import java.io.File
import java.io.IOException

sealed interface SyncState {
    data object Idle : SyncState
    data object Listing : SyncState
    data class Downloading(val done: Int, val total: Int) : SyncState

    /** [stopped]: the folder was too big to list completely. [failed]: downloads that will be tried next time. */
    data class Done(val stopped: Boolean, val failed: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/** An automatic sync skips an ensemble synced this recently: going back and forth doesn't ask Drive each time. */
const val RESYNC_AFTER_MS = 60_000L

/**
 * Syncs ensembles with Drive, one at a time, for the whole app: the home screen syncs all of them, an ensemble's
 * screen its own. Only while one of those screens shows: they [stop] it when they go (to the reader, or out of the
 * app), so nothing syncs while reading or in the background. A screen only stops its own sync: in a crossfade the
 * next screen starts before the last one stops.
 *
 * A sync lists the folder, updates the rows (see [reconcile]), downloads my parts and new versions of what I have
 * opened, and deletes what nothing needs any more. Any other file is downloaded when I open it ([fetch]).
 */
class Syncer private constructor(context: Context) {
    private val app = context.applicationContext
    private val db = VoltiDatabase.get(app)
    private val auth = DriveAuth(app)
    private val store = File(app.filesDir, "drive").let { dir ->
        val storage = app.getSystemService(StorageManager::class.java)
        PdfStore(dir) { storage.getAllocatableBytes(storage.getUuidForPath(app.filesDir)) }
    }

    /** One sync, or clean-up, at a time: they share the store, and clean-up deletes what isn't in use. */
    private val storeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var asked: List<Long> = emptyList()
    private var owner: Any? = null

    private val _states = MutableStateFlow<Map<Long, SyncState>>(emptyMap())

    /** Per ensemble id; an ensemble that isn't there is [SyncState.Idle]. */
    val states: StateFlow<Map<Long, SyncState>> = _states.asStateFlow()

    private val _consent = MutableStateFlow<PendingIntent?>(null)

    /** Google wants me to pick an account or agree first; the result goes to [consented]. */
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

    fun fileFor(md5: String): File = store.fileFor(md5)

    /**
     * Syncs [ids] in turn for [owner], in place of whatever sync was running. Unless [force]d, skips the recently
     * synced.
     */
    fun sync(ids: List<Long>, owner: Any, force: Boolean = false) {
        job?.cancel()
        asked = ids
        this.owner = owner
        job = scope.launch {
            for (id in ids) {
                val ensemble = db.ensembles().get(id) ?: continue
                val last = ensemble.lastSynced
                if (!force && last != null && System.currentTimeMillis() - last < RESYNC_AFTER_MS) continue
                val state = guarded { syncWithAccess(ensemble) } ?: break // Waiting for consent: the rest waits too.
                set(id, state)
            }
        }
    }

    /** [owner] went away. Its sync stops; a half-done download is cleaned up next time. */
    fun stop(owner: Any) {
        if (owner !== this.owner) return
        this.owner = null
        job?.cancel()
        _states.update { states -> states.mapValues { (_, s) -> if (s is SyncState.Listing || s is SyncState.Downloading) SyncState.Idle else s } }
    }

    /** Back from Google's consent screen: carry on with what was asked. */
    fun consented(data: Intent?) {
        _consent.value = null
        if (auth.tokenFrom(data) == null) {
            asked.forEach { set(it, SyncState.Failed("Volti needs access to Google Drive to sync.")) }
            return
        }
        owner?.let { sync(asked, it, force = true) }
    }

    /**
     * I left the ensemble at [time]: what is in it now has been looked at. Here, not in the screen's scope, which is
     * cancelled as the screen goes.
     */
    fun looked(id: Long, time: Long) {
        scope.launch { db.ensembles().looked(id, time) }
    }

    /**
     * Gets one file onto the device, so it can be opened ([open]: and records it as opened, in the same go, so no
     * sync deletes it in between) or because I marked it mine. To open, a version that is here already will do: a
     * newer one comes with the sync, and offline the one here still opens. Waits for at most the one download a sync
     * is doing. Returns a message when it couldn't; null when the file is here.
     */
    suspend fun fetch(ensembleId: Long, fileId: String, open: Boolean): String? = try {
        // Here already and only to open: no need for Google at all, so it works offline.
        val here = storeLock.withLock {
            val file = db.files().all(ensembleId).firstOrNull { it.fileId == fileId }
            file?.localMd5?.also { if (open) db.files().opened(ensembleId, fileId, it) }
        }
        if (here != null && open) null else fetchWithAccess(ensembleId, fileId, open, retry = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: DriveException) {
        Log.w("Volti", "Fetching a file failed: ${e.status}")
        if (e.status == 403 || e.status == 404) "This file isn't in Google Drive any more." else "Google Drive answered with an error (${e.status})."
    } catch (e: IOException) {
        Log.w("Volti", "Fetching a file failed: ${e.message}")
        "This file isn't here yet, and there's no connection to download it."
    } catch (e: Exception) {
        // Play services' own errors (offline with an expired token, among others): a message, not a crash.
        Log.w("Volti", "Fetching a file failed", e)
        "Couldn't reach Google Drive."
    }

    /** Marked mine: download it now, not at the next sync. Here, so leaving the screen doesn't stop it. */
    fun fetchMine(ensembleId: Long, fileId: String) {
        scope.launch { fetch(ensembleId, fileId, open = false) }
    }

    private suspend fun fetchWithAccess(ensembleId: Long, fileId: String, open: Boolean, retry: Boolean): String? =
        when (val access = auth.authorize()) {
            is DriveAccess.NeedsConsent -> {
                _consent.value = access.intent
                "Sign in to Google Drive first."
            }
            is DriveAccess.Granted -> try {
                storeLock.withLock {
                    val file = db.files().all(ensembleId).firstOrNull { it.fileId == fileId } ?: return@withLock "This file is gone."
                    if (file.localMd5 == null) download(DriveApi(access.token), file)
                    if (open) db.files().opened(ensembleId, fileId, file.localMd5 ?: file.md5)
                    null
                }
            } catch (e: DriveException) {
                if (e.status != 401 || !retry) throw e
                auth.forget(access.token)
                fetchWithAccess(ensembleId, fileId, open, retry = false)
            }
        }

    /** Downloads [file]'s version in Drive into the store, unless it is there already. Under [storeLock]. */
    private suspend fun download(api: DriveApi, file: EnsembleFile) {
        if (!withContext(Dispatchers.IO) { store.has(file.md5) }) {
            // Stops within a buffer when the sync is stopped: a blocking read doesn't notice by itself.
            val context = currentCoroutineContext()
            api.download(DriveFolderLink(file.fileId, file.resourceKey)) {
                store.store(file.md5, file.size, it) { context.ensureActive() }
            }
        }
        db.files().downloaded(file.ensembleId, file.fileId, file.md5)
    }

    /** Deletes the ensemble, and the PDFs only it needed. */
    suspend fun remove(ensemble: Ensemble) {
        job?.cancel()
        storeLock.withLock {
            db.ensembles().remove(ensemble) // Its files go with it.
            cleanUp()
        }
    }

    private fun set(id: Long, state: SyncState) = _states.update { it + (id to state) }

    /** Null while waiting for consent. Anything unexpected ends as a message, not a crash. */
    private suspend fun guarded(block: suspend () -> SyncState?): SyncState? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Volti", "Syncing an ensemble failed", e)
        SyncState.Failed("Couldn't reach Google Drive.")
    }

    private suspend fun syncWithAccess(ensemble: Ensemble, retry: Boolean = true): SyncState? =
        when (val access = auth.authorize()) {
            is DriveAccess.NeedsConsent -> {
                _consent.value = access.intent
                null
            }
            is DriveAccess.Granted -> try {
                syncOne(ensemble, access.token)
            } catch (e: DriveException) {
                if (e.status != 401 || !retry) throw e
                // The token ran out: drop it and ask Play services for a fresh one, once.
                auth.forget(access.token)
                syncWithAccess(ensemble, retry = false)
            }
        }

    private suspend fun cleanUp() = withContext(Dispatchers.IO) { store.keepOnly(db.files().versionsInUse().toSet()) }

    /** Throws [DriveException] 401 when the token ran out; every other failure is a [SyncState.Failed]. */
    private suspend fun syncOne(ensemble: Ensemble, token: String): SyncState {
        val id = ensemble.id
        val folder = DriveFolderLink(ensemble.folderId, ensemble.resourceKey)
        val api = DriveApi(token)
        return try {
            set(id, SyncState.Listing)
            if (api.folderInfo(folder).mimeType != FOLDER_TYPE) return SyncState.Failed("The link is to a file, not a folder.")
            val listing = walkDriveFolder(folder) { f, page -> api.listPage(f, page) }
            val files = db.files()
            val todo = storeLock.withLock {
                // The first listing finds what is there; only later ones bring new files. By the rows, not by
                // lastSynced: a first sync stopped during its downloads has listed already.
                val now = System.currentTimeMillis()
                files.replace(id) { old ->
                    reconcile(id, old, listing.pdfs, complete = !listing.stopped, addedAt = now.takeIf { old.isNotEmpty() })
                }
                val all = files.all(id)
                // Only what I play, and what I have opened, stays on the device; the rest comes when I open it. Until
                // I have marked parts in an ensemble, nothing that is here goes: it may be all I have offline.
                if (all.any { it.mine }) files.forgetUnused(id)
                files.all(id).filter { !it.removed && it.localMd5 != it.md5 && (it.mine || it.seenMd5 != null) }
            }
            var failed = 0
            todo.forEachIndexed { i, file ->
                set(id, SyncState.Downloading(i, todo.size))
                try {
                    // One file at a time under the lock, so opening a file waits for one download, not all.
                    storeLock.withLock {
                        val now = files.all(id).firstOrNull { it.fileId == file.fileId }
                        if (now != null && now.md5 == file.md5 && now.localMd5 != now.md5) download(api, now)
                    }
                } catch (e: DriveException) {
                    if (e.status == 401) throw e // Start over with a new token.
                    failed++
                } catch (e: IOException) {
                    Log.w("Volti", "A download failed: ${e.message}") // Not the name: it may be private.
                    failed++
                }
            }
            storeLock.withLock { cleanUp() }
            db.ensembles().synced(id, System.currentTimeMillis())
            SyncState.Done(listing.stopped, failed)
        } catch (e: DriveException) {
            when (e.status) {
                401 -> throw e
                403, 404 -> SyncState.Failed("This Google account can't see that folder. Is it shared with you?")
                else -> SyncState.Failed("Google Drive answered with an error (${e.status}). Try again later.")
            }
        } catch (e: IOException) {
            Log.w("Volti", "Syncing an ensemble failed", e) // Not the folder id: for a link-shared folder, that is the link.
            SyncState.Failed("No connection to Google Drive.")
        }
    }

    companion object {
        // Holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Syncer? = null

        fun get(context: Context): Syncer = instance ?: synchronized(this) {
            instance ?: Syncer(context).also { instance = it }
        }
    }
}

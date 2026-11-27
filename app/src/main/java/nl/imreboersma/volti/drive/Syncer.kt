package nl.imreboersma.volti.drive

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.db.localKey
import nl.imreboersma.volti.annotate.MarkWrites
import nl.imreboersma.volti.data.FolderStore
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface SyncState {
    data object Idle : SyncState
    data object Listing : SyncState
    /** File [done] of [total]: [now] is downloading, [waiting] are still to come in this sync. */
    data class Downloading(val done: Int, val total: Int, val now: String? = null, val waiting: Set<String> = emptySet()) : SyncState

    /** [stopped]: the folder was too big to list completely. [failed]: downloads that will be tried next time. */
    data class Done(val stopped: Boolean, val failed: Int) : SyncState
    data class Failed(val problem: DriveProblem) : SyncState
}

/** How long a copy from another app waits to be imported before it is cleaned up. */
private const val INCOMING_KEPT_MS = 24 * 60 * 60 * 1000L

/**
 * A PDF copied in from another app, waiting to be imported: its [name] there, its [copy] next to the store (a file
 * name), its [md5], and the [folders] it was in below what I imported (a folder's subfolders; none for a file).
 */
@Serializable
data class Incoming(val name: String, val copy: String, val md5: String, val folders: List<String> = emptyList())

/**
 * What one [Syncer.receive] copied in, and what it couldn't, with why. Kept as [batch], a file next to the copies,
 * so a route carries only its id however many PDFs a folder held, and it outlives the process. [oldFolder]: the
 * folder Volti read in place before; it is let go once this is imported.
 */
@Serializable
data class Received(
    val batch: String,
    val incoming: List<Incoming>,
    val problems: List<Pair<String, Rejection>>,
    val oldFolder: Boolean = false,
)

enum class ImportStep { Copying, Importing }

/** How far an import is: [done] of [total] files in [step]. */
data class ImportProgress(val step: ImportStep, val done: Int, val total: Int)

/** The name another app gives a file; the end of its address if it gives none, or can't say. */
fun displayName(resolver: ContentResolver, uri: Uri): String {
    // Any app's provider answers this, and may throw instead.
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
    return name ?: uri.lastPathSegment ?: "Imported.pdf"
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
    // filesDir, from the data directory's path: the getter reads the disk, and Syncer is made on the main thread.
    private val files = File(app.applicationInfo.dataDir, "files")
    private val store = File(files, "drive").let { dir ->
        val storage = app.getSystemService(StorageManager::class.java)
        PdfStore(dir) { storage.getAllocatableBytes(storage.getUuidForPath(files)) }
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

    init {
        // Copies of shares that were never imported or let go (the process ended at the question): after a day.
        scope.launch(Dispatchers.IO) { store.clearIncoming(olderThan = System.currentTimeMillis() - INCOMING_KEPT_MS) }
    }

    /**
     * Syncs [ids] in turn for [owner], in place of whatever sync was running. Unless [force]d, skips the recently
     * synced.
     */
    fun sync(ids: List<Long>, owner: Any, force: Boolean = false) {
        // Already syncing these (a second pull, or the library handing over to an ensemble's screen): carry on.
        // Starting again would throw away the file halfway through its download.
        if (job?.isActive == true && asked.containsAll(ids)) {
            this.owner = owner
            return
        }
        job?.cancel()
        settle()
        asked = ids
        this.owner = owner
        job = scope.launch {
            // First ask Drive about every ensemble, then download: the checking is one short stretch, and from then on
            // every ensemble shows what it will download.
            val listed = mutableListOf<Pair<Ensemble, Listed>>()
            for (id in ids) {
                val ensemble = db.ensembles().get(id) ?: continue
                if (ensemble.folderId == null) continue // Only imports: nothing in Drive.
                val last = ensemble.lastSynced
                if (!force && last != null && System.currentTimeMillis() - last < RESYNC_AFTER_MS) continue
                set(id, SyncState.Listing)
                when (val result = withAccess { token -> list(ensemble, token) }) {
                    null -> return@launch signedOut(id) // Waiting for consent: the rest waits too.
                    is Outcome.Failed -> set(id, result.state)
                    is Outcome.Ok -> {
                        listed += ensemble to result.value
                        set(id, planned(result.value))
                    }
                }
            }
            for ((ensemble, plan) in listed) {
                when (val result = withAccess { token -> downloadAll(ensemble, plan, token) }) {
                    null -> return@launch signedOut(ensemble.id)
                    is Outcome.Failed -> set(ensemble.id, result.state)
                    is Outcome.Ok -> set(ensemble.id, result.value)
                }
            }
        }
    }

    /** Asking for consent stopped the sync: that ensemble says so, the others have nothing going on any more. */
    private fun signedOut(id: Long) {
        settle()
        set(id, SyncState.Failed(DriveProblem.SignedOut))
    }

    /** Nothing is listed or downloaded any more: no ensemble, and no sheet, says it is. */
    private fun settle() =
        _states.update { states -> states.mapValues { (_, s) -> if (s is SyncState.Listing || s is SyncState.Downloading) SyncState.Idle else s } }

    /** [owner] went away. Its sync stops; a half-done download is cleaned up next time. */
    fun stop(owner: Any) {
        if (owner !== this.owner) return
        this.owner = null
        job?.cancel()
        settle()
    }

    /** Back from Google's consent screen: carry on with what was asked. */
    fun consented(data: Intent?) {
        _consent.value = null
        if (auth.tokenFrom(data) == null) {
            asked.forEach { set(it, SyncState.Failed(DriveProblem.SignedOut)) }
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
     * newer one comes with the sync, and offline the one here still opens. Never waits for another file's download: a
     * transfer holds nothing. Returns what went wrong when it couldn't; null when the file is here.
     */
    suspend fun fetch(ensembleId: Long, fileId: String, open: Boolean): DriveProblem? = try {
        // Here already and only to open: no need for Google at all, so it works offline.
        val file = storeLock.withLock {
            db.files().all(ensembleId).firstOrNull { it.fileId == fileId }?.also { file ->
                file.localMd5?.let { if (open) db.files().opened(ensembleId, fileId, it) }
            }
        }
        when {
            file?.localMd5 != null && (open || file.imported) -> null
            file?.imported == true -> DriveProblem.ImportGone // An import only ever was here.
            else -> fetchWithAccess(ensembleId, fileId, open, retry = true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DriveException) {
        Log.w("Volti", "Can't download a file: Google Drive answered ${e.status}", e)
        if (e.status == 403 || e.status == 404) DriveProblem.FileGone else DriveProblem.DriveError(e.status)
    } catch (e: IOException) {
        Log.w("Volti", "Can't download a file", e)
        DriveProblem.Unreachable
    } catch (e: Exception) {
        // Play services' own errors (offline with an expired token, among others): a message, not a crash.
        Log.w("Volti", "Can't download a file", e)
        DriveProblem.Unreachable
    }

    /**
     * Copies [uris] from other apps in now, next to the store, while Volti may still read them: another app's leave
     * to read a file ends with the screen it was given to. Here, not in a screen's scope, so leaving the screen
     * doesn't stop it; with no lock, so a slow app holds up only its own files. Completes with the copies, which
     * [import] takes in, and what couldn't be copied.
     */
    fun receive(
        uris: List<Uri>,
        folders: List<List<String>> = uris.map { emptyList() },
        oldFolder: Boolean = false,
        into: Long? = null,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Deferred<Received> = scope.async { try {
        val resolver = app.contentResolver
        val copies = mutableListOf<Incoming>()
        val problems = mutableListOf<Pair<String, Rejection>>()
        for ((i, pair) in uris.zip(folders).withIndex()) {
            val (uri, inFolders) = pair
            progress(i, uris.size)
            showImport(into, ImportProgress(ImportStep.Copying, i, uris.size))
            val name = withContext(Dispatchers.IO) { displayName(resolver, uri) }
            try {
                val (copy, md5) = withContext(Dispatchers.IO) {
                    (resolver.openInputStream(uri) ?: throw IOException("Can't read a shared PDF")).use(store::receive)
                }
                copies += Incoming(name, copy.name, md5, inFolders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadRejected) {
                Log.w("Volti", "Can't import a PDF", e) // Not the name: it may be private.
                problems += name to e.reason
            } catch (e: Exception) {
                // Another app's provider can fail in any way: that file isn't imported, the rest are.
                Log.w("Volti", "Can't import a PDF", e)
                problems += name to Rejection.Unreadable
            }
        }
        val batch = "batch-${UUID.randomUUID()}"
        Received(batch, copies, problems, oldFolder).also { received ->
            withContext(Dispatchers.IO) {
                // Whole or not at all: a process ending mid-write leaves no half batch.
                val temp = store.incoming("$batch.tmp")
                temp.writeText(Json.encodeToString(received))
                if (!temp.renameTo(store.incoming(batch))) throw IOException("Can't keep an import's list")
            }
        }
    } finally {
        showImport(into, null)
    } }

    /** What [receive] copied in as [batch]; null once it is imported, let go or cleaned up, or if it can't be read. */
    suspend fun received(batch: String): Received? = withContext(Dispatchers.IO) {
        val file = store.incoming(batch).takeIf { it.isFile } ?: return@withContext null
        try {
            Json.decodeFromString<Received>(file.readText())
        } catch (e: Exception) {
            Log.w("Volti", "Can't read an import's list", e)
            null
        }
    }

    /**
     * Takes what [receive] copied in, [received], into ensemble [ensembleId]: each in the folder [into], below it in
     * the subfolders it was in. One at a time under the store's lock. With [replace], one that has the name of an
     * imported file in its folder is a new version of it, as when a Drive file changes: its marks stay, and I'm told
     * the score changed under them. Without, it gets a name of its own. A new one takes over the marks I made on the
     * same PDF as a local file. Completes with what couldn't be taken in (a copy cleaned up meanwhile).
     */
    fun import(
        ensembleId: Long,
        into: List<String>,
        received: Received,
        replace: Boolean,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Deferred<List<Pair<String, Rejection>>> =
        scope.async { try {
            val problems = mutableListOf<Pair<String, Rejection>>()
            // Read once, kept up to date here: a folder of thousands doesn't read the table thousands of times.
            val rows = db.files().all(ensembleId).toMutableList()
            for ((i, file) in received.incoming.withIndex()) {
                progress(i, received.incoming.size)
                showImport(ensembleId, ImportProgress(ImportStep.Importing, i, received.incoming.size))
                val folders = into + file.folders
                try {
                    storeLock.withLock {
                        val size = withContext(Dispatchers.IO) {
                            store.keep(store.incoming(file.copy), file.md5)
                            store.fileFor(file.md5).length()
                        }
                        val here = rows.filter { it.folders == folders }
                        val replaced = if (replace) here.firstOrNull { it.imported && it.name == file.name } else null
                        val row = replaced?.copy(md5 = file.md5, localMd5 = file.md5, size = size) ?: EnsembleFile(
                            ensembleId, UUID.randomUUID().toString(), null, freeName(file.name, here.mapTo(HashSet()) { it.name }),
                            folders, file.md5, size, localMd5 = file.md5, addedAt = System.currentTimeMillis(), imported = true,
                        )
                        db.files().save(listOf(row))
                        rows.remove(replaced)
                        rows += row
                        // The marks I made on exactly this PDF when Volti read it in place, also onto a replaced file
                        // (once any still on their way are in).
                        MarkWrites.flush()
                        db.annotations().moveScore(from = localKey(file.md5), to = row.scoreKey)
                    }
                } catch (e: IOException) {
                    Log.w("Volti", "Can't import a PDF", e)
                    problems += file.name to Rejection.Unreadable
                }
            }
            withContext(Dispatchers.IO) { store.incoming(received.batch).delete() }
            // Its PDFs are Volti's own now: the folder read in place can go, and the offer with it.
            if (received.oldFolder) FolderStore(app).forget()
            problems
        } finally {
            showImport(ensembleId, null)
        } }

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())

    /** The files downloading right now, by file id: for the sync, to open one, or because I marked it mine. */
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    private val _importing = MutableStateFlow<Map<Long, ImportProgress>>(emptyMap())

    /**
     * Per ensemble id, how far an import into it is. Here, not in a screen: the import runs on when I leave, and the
     * screen I come back to still sees it.
     */
    val importing: StateFlow<Map<Long, ImportProgress>> = _importing.asStateFlow()

    private fun showImport(ensembleId: Long?, progress: ImportProgress?) {
        if (ensembleId == null) return
        _importing.update { if (progress == null) it - ensembleId else it + (ensembleId to progress) }
    }

    /** What I decided not to import: the copies and the batch go. */
    fun discard(received: Received) {
        scope.launch(Dispatchers.IO) {
            received.incoming.forEach { store.incoming(it.copy).delete() }
            store.incoming(received.batch).delete()
        }
    }

    /** An imported file goes: its row, its place in setlists and its marks. The PDF goes at the next clean-up. */
    suspend fun delete(file: EnsembleFile) {
        if (!file.imported) return // Drive's files come and go with the folder.
        MarkWrites.flush() // A mark on it still on its way would land after its marks are gone.
        storeLock.withLock {
            db.deleteImported(file)
            cleanUp() // An ensemble of imports never syncs, so its clean-up is here.
        }
    }

    /**
     * The files that an import of [received] into [into] of ensemble [ensembleId] would replace, by the name they show
     * here (mine, if I gave one): that is how I know them in the list.
     */
    suspend fun alreadyImported(ensembleId: Long, into: List<String>, received: Received): List<String> {
        val here = db.files().all(ensembleId).filter { it.imported }.associateBy { it.folders to it.name }
        return received.incoming.mapNotNull { here[(into + it.folders) to it.name]?.shownName }
    }

    /** Marked mine: download it now, not at the next sync. Here, so leaving the screen doesn't stop it. */
    fun fetchMine(ensembleId: Long, fileId: String) {
        scope.launch { fetch(ensembleId, fileId, open = false) }
    }

    private suspend fun fetchWithAccess(ensembleId: Long, fileId: String, open: Boolean, retry: Boolean): DriveProblem? =
        when (val access = auth.authorize()) {
            is DriveAccess.NeedsConsent -> {
                _consent.value = access.intent
                DriveProblem.SignedOut
            }
            is DriveAccess.Granted -> try {
                val file = db.files().all(ensembleId).firstOrNull { it.fileId == fileId }
                if (file == null) {
                    DriveProblem.FileGone
                } else {
                    // Beside whatever the sync downloads: neither waits for the other's transfer.
                    download(DriveApi(access.token), file) { if (open) db.files().opened(ensembleId, fileId, file.md5) }
                    null
                }
            } catch (e: DriveException) {
                if (e.status != 401 || !retry) throw e
                auth.forget(access.token)
                fetchWithAccess(ensembleId, fileId, open, retry = false)
            }
        }

    /** Downloads [file]'s version in Drive into the store, unless it is there already. Under [storeLock]. */
    private suspend fun download(api: DriveApi, file: EnsembleFile, alsoUnderLock: suspend () -> Unit = {}) {
        var copy: File? = null
        _downloading.update { it + file.fileId }
        try {
            while (true) {
                // Here already (checked under the lock, so no clean-up comes between), or just copied: into the store
                // and recorded in one go.
                val done = storeLock.withLock {
                    val here = withContext(Dispatchers.IO) { store.has(file.md5) }
                    val taking = copy
                    if (taking == null && !here) return@withLock false
                    if (taking != null) withContext(Dispatchers.IO) { store.keep(taking, file.md5) }
                    copy = null
                    db.files().downloaded(file.ensembleId, file.fileId, file.md5)
                    alsoUnderLock()
                    true
                }
                if (done) return
                // The transfer without the lock: a minute of it holds up nothing else. Stops within a buffer when the
                // sync is stopped: a blocking read doesn't notice by itself.
                val context = currentCoroutineContext()
                copy = api.download(DriveFolderLink(file.fileId, file.resourceKey)) {
                    store.download(file.md5, file.size, it) { context.ensureActive() }
                }
            }
        } finally {
            _downloading.update { it - file.fileId }
            // Stopped (the reader opened, the screen went) between the transfer and the store: no full copy left behind.
            copy?.let { withContext(NonCancellable + Dispatchers.IO) { it.delete() } }
        }
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

    private suspend fun cleanUp() = withContext(Dispatchers.IO) { store.keepOnly(db.files().versionsInUse().toSet()) }

    /** A step of a sync: its result, or the state that says why it couldn't. */
    private sealed interface Outcome<out T> {
        class Ok<T>(val value: T) : Outcome<T>
        class Failed(val state: SyncState.Failed) : Outcome<Nothing>
    }

    /**
     * Runs [step] with a Drive token: null while Google wants consent first. A token that ran out (401) is dropped and
     * the step runs once more with a fresh one; any other failure ends as a [SyncState.Failed], never a crash.
     */
    private suspend fun <T> withAccess(retry: Boolean = true, step: suspend (token: String) -> T): Outcome<T>? = try {
        when (val access = auth.authorize()) {
            is DriveAccess.NeedsConsent -> {
                _consent.value = access.intent
                null
            }
            is DriveAccess.Granted -> try {
                Outcome.Ok(step(access.token))
            } catch (e: DriveException) {
                if (e.status != 401 || !retry) throw e
                auth.forget(access.token)
                withAccess(retry = false, step)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotAFolder) {
        Outcome.Failed(SyncState.Failed(DriveProblem.NotAFolder))
    } catch (e: DriveException) {
        Outcome.Failed(SyncState.Failed(if (e.status == 403 || e.status == 404) DriveProblem.NotShared else DriveProblem.DriveError(e.status)))
    } catch (e: IOException) {
        Log.w("Volti", "Can't sync an ensemble", e) // Not the folder id: for a link-shared folder, that is the link.
        Outcome.Failed(SyncState.Failed(DriveProblem.Unreachable))
    } catch (e: Exception) {
        // Play services' own errors (offline with an expired token, among others).
        Log.w("Volti", "Can't sync an ensemble", e)
        Outcome.Failed(SyncState.Failed(DriveProblem.Unreachable))
    }

    private class NotAFolder : IOException("The link is to a file")

    /** What a listing found to download, and whether it stopped at the limit. */
    private class Listed(val todo: List<EnsembleFile>, val stopped: Boolean)

    /** Before its downloads start: what is to come, or done already. */
    private fun planned(listed: Listed): SyncState =
        if (listed.todo.isEmpty()) SyncState.Done(listed.stopped, 0)
        else SyncState.Downloading(0, listed.todo.size, now = null, waiting = listed.todo.mapTo(HashSet()) { it.fileId })

    /** Lists the ensemble's folder and updates its rows (see [reconcile]); what is to be downloaded. */
    private suspend fun list(ensemble: Ensemble, token: String): Listed {
        val id = ensemble.id
        val folder = DriveFolderLink(ensemble.folderId ?: return Listed(emptyList(), false), ensemble.resourceKey)
        val api = DriveApi(token)
        if (api.folderInfo(folder).mimeType != FOLDER_TYPE) throw NotAFolder()
        val listing = walkDriveFolder(folder) { f, page -> api.listPage(f, page) }
        val files = db.files()
        val todo = storeLock.withLock {
            // The first listing finds what is there; only later ones bring new files. By the rows, not by
            // lastSynced: a first sync stopped during its downloads has listed already.
            val now = System.currentTimeMillis()
            files.replace(id) { old ->
                // Imports don't count: the first listing of Drive's files still finds nothing new.
                reconcile(id, old, listing.pdfs, complete = !listing.stopped, addedAt = now.takeIf { old.any { !it.imported } })
            }
            val all = files.all(id)
            // Only what I play, and what I have opened, stays on the device; the rest comes when I open it. Until
            // I have marked parts in an ensemble, nothing that is here goes: it may be all I have offline.
            if (all.any { it.mine && !it.imported }) files.forgetUnused(id)
            files.all(id).filter { !it.removed && it.localMd5 != it.md5 && (it.mine || it.seenMd5 != null) }
        }
        if (todo.isEmpty()) synced(id)
        return Listed(todo, listing.stopped)
    }

    /** Downloads what [list] found, one at a time; a file that fails is tried again next time. */
    private suspend fun downloadAll(ensemble: Ensemble, listed: Listed, token: String): SyncState {
        val id = ensemble.id
        val todo = listed.todo
        if (todo.isEmpty()) return SyncState.Done(listed.stopped, 0)
        val api = DriveApi(token)
        val files = db.files()
        var failed = 0
        todo.forEachIndexed { i, file ->
            set(id, SyncState.Downloading(i, todo.size, file.fileId, todo.drop(i + 1).mapTo(HashSet()) { it.fileId }))
            try {
                // The transfer without the lock: opening another file meanwhile doesn't wait for it.
                val now = files.all(id).firstOrNull { it.fileId == file.fileId }
                if (now != null && now.md5 == file.md5 && now.localMd5 != now.md5) download(api, now)
            } catch (e: DriveException) {
                if (e.status == 401) throw e // Start over with a new token.
                failed++
            } catch (e: IOException) {
                Log.w("Volti", "Can't download a file", e) // Not the name: it may be private.
                failed++
            }
        }
        synced(id)
        return SyncState.Done(listed.stopped, failed)
    }

    /** Done with an ensemble: what nothing needs any more goes, and it was synced now. */
    private suspend fun synced(id: Long) {
        storeLock.withLock { cleanUp() }
        db.ensembles().synced(id, System.currentTimeMillis())
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

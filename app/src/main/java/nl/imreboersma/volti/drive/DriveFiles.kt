package nl.imreboersma.volti.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val FOLDER_TYPE = "application/vnd.google-apps.folder"
const val PDF_TYPE = "application/pdf"

/** One file or folder as Drive's `files.list` returns it, with only the fields Volti asks for. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Changes with every new version of the content. Absent for folders. */
    val md5Checksum: String? = null,
    /** Bytes, as a string in Drive's JSON. Absent for folders. */
    val size: String? = null,
    val resourceKey: String? = null,
)

@Serializable
data class DrivePage(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

private val json = Json { ignoreUnknownKeys = true }

fun parsePage(body: String): DrivePage = json.decodeFromString(body)

/** The folder's own name, from `files.get`. */
@Serializable
data class DriveFolderInfo(val name: String, val mimeType: String)

fun parseFolderInfo(body: String): DriveFolderInfo = json.decodeFromString(body)

/** A PDF found in the folder, with the names of the subfolders on the way to it (empty: at the top). */
data class DriveEntry(val file: DriveFile, val folders: List<String>)

/** What a walk found. [stopped]: it hit the limit, so the deepest files are left out. */
data class DriveListing(val pdfs: List<DriveEntry>, val folders: Int, val stopped: Boolean)

/** Same limit as the local folder walk: a folder this big is a mistake, not a library. */
const val DRIVE_WALK_LIMIT = 5000

/**
 * Every PDF in [root] and its subfolders, breadth first so the top levels are complete if the [limit] stops it.
 * Other file types are left out, shortcuts included, and so is anything whose id isn't a well-formed Drive id: ids
 * go into the next query. A folder that sits in two places is walked once.
 * [list] fetches one page of a folder's children.
 */
suspend fun walkDriveFolder(
    root: DriveFolderLink,
    limit: Int = DRIVE_WALK_LIMIT,
    list: suspend (folder: DriveFolderLink, pageToken: String?) -> DrivePage,
): DriveListing {
    val pdfs = mutableListOf<DriveEntry>()
    val seen = mutableSetOf(root.id)
    val queue = ArrayDeque(listOf(root to emptyList<String>()))
    var folders = 0
    var seenEntries = 0
    while (queue.isNotEmpty()) {
        val (folder, path) = queue.removeFirst()
        var token: String? = null
        do {
            val page = list(folder, token)
            for (file in page.files) {
                if (++seenEntries > limit) return DriveListing(pdfs, folders, stopped = true)
                if (!isDriveId(file.id)) continue
                when (file.mimeType) {
                    FOLDER_TYPE -> if (seen.add(file.id)) {
                        folders++
                        queue.addLast(DriveFolderLink(file.id, file.resourceKey) to path + file.name)
                    }
                    PDF_TYPE -> pdfs += DriveEntry(file, path)
                }
            }
            token = page.nextPageToken
        } while (token != null)
    }
    return DriveListing(pdfs, folders, stopped = false)
}

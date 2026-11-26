package nl.imreboersma.volti.drive

import java.net.URI
import java.net.URLDecoder

/** A Drive folder: its id, and the resource key that folders shared by link before 2021 also need. */
data class DriveFolderLink(val id: String, val resourceKey: String? = null)

/** Drive ids are URL-safe base64. Checked strictly: ids go into the API's query string and URLs. */
private val DRIVE_ID = Regex("[A-Za-z0-9_-]{1,100}")

internal fun isDriveId(id: String) = DRIVE_ID.matches(id)

/** Pasted text taken as a bare id: long enough not to be a word. Real ids are 19 characters or more. */
private val BARE_ID = Regex("[A-Za-z0-9_-]{10,100}")

/**
 * The folder in a pasted link: `https://drive.google.com/drive/folders/<id>`, with or without `/u/0`, `?usp=sharing`
 * or `?resourcekey=`; the older `open?id=<id>`; or the bare id. Null for anything else, a link to a file included.
 */
fun parseFolderLink(text: String): DriveFolderLink? {
    val trimmed = text.trim()
    if (BARE_ID.matches(trimmed)) return DriveFolderLink(trimmed)
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "drive.google.com") return null
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    val query = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
    val id = when {
        segments.firstOrNull() == "drive" && "folders" in segments -> segments.getOrNull(segments.indexOf("folders") + 1)
        segments == listOf("open") -> query["id"]
        else -> null
    }
    if (id == null || !DRIVE_ID.matches(id)) return null
    return DriveFolderLink(id, query["resourcekey"]?.takeIf { DRIVE_ID.matches(it) })
}

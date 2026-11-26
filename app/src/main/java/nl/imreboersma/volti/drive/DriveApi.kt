package nl.imreboersma.volti.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Drive said no. [status] is the HTTP status: 401 means the access token ran out, 403 and 404 no access. */
class DriveException(val status: Int, message: String) : IOException(message)

/**
 * The two read-only calls Volti makes to Drive's REST API, over plain HTTPS: no client library, so nothing more
 * than this can be reached. [token] is the access token from the Authorization API.
 */
class DriveApi(private val token: String) {

    suspend fun folderInfo(folder: DriveFolderLink): DriveFolderInfo =
        parseFolderInfo(get("files/${folder.id}?fields=name,mimeType&supportsAllDrives=true", folder))

    suspend fun listPage(folder: DriveFolderLink, pageToken: String?): DrivePage {
        val query = "'${folder.id}' in parents and trashed = false" // Ids are checked: parseFolderLink, walkDriveFolder.
        val params = buildString {
            append("files?q=").append(encode(query))
            append("&fields=").append(encode("nextPageToken,files(id,name,mimeType,md5Checksum,size,resourceKey)"))
            append("&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true")
            if (pageToken != null) append("&pageToken=").append(encode(pageToken))
        }
        return parsePage(get(params, folder))
    }

    private suspend fun get(path: String, folder: DriveFolderLink): String = withContext(Dispatchers.IO) {
        val connection = URL("https://www.googleapis.com/drive/v3/$path").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            folder.resourceKey?.let { connection.setRequestProperty("X-Goog-Drive-Resource-Keys", "${folder.id}/$it") }
            val status = connection.responseCode
            if (status != 200) throw DriveException(status, "Drive answered $status")
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

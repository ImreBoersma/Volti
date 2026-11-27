package nl.imreboersma.volti.drive

import nl.imreboersma.volti.db.EnsembleFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Largest PDF Volti downloads. A 200 dpi scan of a long part is tens of MB; this is well past that. */
const val MAX_PDF_BYTES = 200L * 1024 * 1024

/** Left free on the tablet after a download, so the copy never fills it up. */
const val KEEP_FREE_BYTES = 500L * 1024 * 1024

/**
 * The ensemble's rows after a walk of its folder found [found]:
 * - new PDFs are added, not yet downloaded;
 * - names, folders and the version in Drive follow Drive; what is on the tablet, and what I saw, stay as they were
 *   (downloads and opening the score change those);
 * - what is no longer there is dropped, unless I opened it or marked it mine: then it stays, marked removed;
 * - unless the walk was not [complete]: then what it didn't reach is left as it is;
 * - new PDFs get [addedAt]: null on the ensemble's first sync, where nothing counts as new;
 * - imported files aren't Drive's: they stay exactly as they are.
 * PDFs without a well-formed checksum are left out (Drive gives one for every uploaded file; it becomes a file name),
 * and a PDF in two subfolders counts once, in the first.
 */
fun reconcile(
    ensembleId: Long,
    old: List<EnsembleFile>,
    found: List<DriveEntry>,
    complete: Boolean = true,
    addedAt: Long? = null,
): List<EnsembleFile> {
    val (imported, fromDrive) = old.partition { it.imported }
    val before = fromDrive.associateBy { it.fileId }
    val now = found.filter { it.file.md5Checksum?.matches(MD5) == true }.distinctBy { it.file.id }.map { entry ->
        val file = entry.file
        val size = file.size?.toLongOrNull() ?: 0
        before[file.id]?.copy(
            resourceKey = file.resourceKey,
            name = file.name,
            folders = entry.folders,
            md5 = file.md5Checksum!!,
            size = size,
            removed = false,
        ) ?: EnsembleFile(ensembleId, file.id, file.resourceKey, file.name, entry.folders, file.md5Checksum!!, size, addedAt = addedAt)
    }
    val seen = now.mapTo(HashSet()) { it.fileId }
    val missing = fromDrive.filter { it.fileId !in seen }
    return imported + now + when {
        !complete -> missing
        // Mine but never downloaded: nothing to play from, so it goes like the rest.
        else -> missing.filter { it.seenMd5 != null || (it.mine && it.localMd5 != null) }.map { it.copy(removed = true) }
    }
}

private val MD5 = Regex("[0-9a-f]{32}")

/** Why a PDF wasn't taken in, downloaded or imported. */
enum class Rejection { TooLarge, NotAPdf, WrongChecksum, NoSpace, Unreadable }

class DownloadRejected(val reason: Rejection, message: String) : IOException(message)

/** [name], or with " (2)", " (3)"… before its extension when [taken] has it: two files in a folder never share a name. */
fun freeName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return generateSequence(2) { it + 1 }.map { "${name.take(dot)} ($it)${name.drop(dot)}" }.first { it !in taken }
}

/**
 * Copies [input] to [target]: at most [limit] bytes, starting like a PDF (`%PDF-` within the first 1024 bytes, as
 * the format allows), with md5 [expectedMd5] when given. Anything else is deleted and rejected: what lands on the
 * tablet is what Drive said it would be. [check] runs between buffers and may throw to stop (a cancelled sync).
 * Returns the md5.
 */
fun copyChecked(input: InputStream, target: File, expectedMd5: String?, limit: Long = MAX_PDF_BYTES, check: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("MD5")
    try {
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            val head = ByteArray(1024)
            var headSize = 0
            var total = 0L
            while (true) {
                check()
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw DownloadRejected(Rejection.TooLarge, "Larger than ${limit / 1024 / 1024} MB")
                if (headSize < head.size) {
                    val take = minOf(n, head.size - headSize)
                    System.arraycopy(buffer, 0, head, headSize, take)
                    headSize += take
                }
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
            if (!head.copyOf(headSize).decodeToString().contains("%PDF-")) throw DownloadRejected(Rejection.NotAPdf, "Not a PDF")
        }
        val md5 = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedMd5 != null && !md5.equals(expectedMd5, ignoreCase = true)) throw DownloadRejected(Rejection.WrongChecksum, "Checksum doesn't match")
        return md5
    } catch (e: Exception) {
        target.delete()
        throw e
    }
}

/**
 * Where the PDFs live: one file per version, named by its md5. [freeBytes] is the space a download may use: on the
 * tablet, what Android can make free (cached data of other apps included).
 */
class PdfStore(private val dir: File, private val freeBytes: () -> Long) {
    fun fileFor(md5: String): File = File(dir, "$md5.pdf")

    fun has(md5: String) = fileFor(md5).isFile

    /**
     * Downloads version [md5] next to the store, as an import is copied: a transfer can take a minute, and the store
     * isn't held meanwhile. The copy passed [copyChecked]; [keep] takes it in.
     */
    fun download(md5: String, size: Long, input: InputStream, check: () -> Unit = {}): File {
        incoming.mkdirs()
        if (freeBytes() < size + KEEP_FREE_BYTES) throw DownloadRejected(Rejection.NoSpace, "Not enough space on this device")
        val temp = File(incoming, "${UUID.randomUUID()}.tmp")
        copyChecked(input, temp, md5, check = check)
        return temp
    }

    /** Where imports are copied first: outside the store, so a clean-up can't sweep one away mid-copy. */
    private val incoming = File(dir.parentFile, "${dir.name}-incoming")

    /**
     * Copies a PDF from the device or another app next to the store, under the same limits as a download: the copy,
     * and its md5. Slow input (a cloud provider) holds up nothing; [keep] then takes it in.
     */
    fun receive(input: InputStream): Pair<File, String> {
        incoming.mkdirs()
        if (freeBytes() < KEEP_FREE_BYTES) throw DownloadRejected(Rejection.NoSpace, "Not enough space on this device")
        val temp = File(incoming, "${UUID.randomUUID()}.tmp")
        return temp to copyChecked(input, temp, expectedMd5 = null)
    }

    /** A [receive]d copy into the store, named by its [md5]; the same PDF twice is stored once. */
    fun keep(copy: File, md5: String) {
        dir.mkdirs()
        if (has(md5)) {
            copy.delete()
        } else if (!copy.renameTo(fileFor(md5))) {
            copy.delete()
            throw IOException("Can't store an imported PDF")
        }
    }

    /** A [receive]d copy, by its file name. */
    fun incoming(name: String) = File(incoming, File(name).name)

    /** Copies from before [olderThan]: never imported, never let go. */
    fun clearIncoming(olderThan: Long) {
        incoming.listFiles()?.forEach { if (it.lastModified() < olderThan) it.delete() }
    }

    /** Deletes every version not in [inUse], and downloads that never finished. */
    fun keepOnly(inUse: Set<String>) {
        dir.listFiles()?.forEach { file ->
            val md5 = file.name.removeSuffix(".pdf")
            if (file.name.endsWith(".tmp") || md5 !in inUse) file.delete()
        }
    }
}

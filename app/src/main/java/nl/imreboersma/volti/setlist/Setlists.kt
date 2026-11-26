package nl.imreboersma.volti.setlist

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile

/** A concert or a rehearsal: pieces of one ensemble, in the order I play them. */
@Entity(
    tableName = "setlist",
    foreignKeys = [ForeignKey(entity = Ensemble::class, parentColumns = ["id"], childColumns = ["ensembleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["ensembleId"])],
)
data class Setlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ensembleId: Long,
    val name: String,
    val createdAt: Long,
)

/** A piece in a setlist: a Drive file of its ensemble, at [position] (0 first). A piece is in a setlist once. */
@Entity(
    tableName = "setlist_entry",
    primaryKeys = ["setlistId", "fileId"],
    foreignKeys = [ForeignKey(entity = Setlist::class, parentColumns = ["id"], childColumns = ["setlistId"], onDelete = ForeignKey.CASCADE)],
)
data class SetlistEntry(val setlistId: Long, val fileId: String, val position: Int)

/** A setlist's piece with its file as it is now; [file] is null once the file is gone from the ensemble. */
data class SetlistPiece(
    val fileId: String,
    val position: Int,
    @Embedded(prefix = "file_") val file: EnsembleFile?,
)

@Dao
interface SetlistDao {
    /** An ensemble's setlists, the newest first. */
    @Query("SELECT * FROM setlist WHERE ensembleId = :ensembleId ORDER BY createdAt DESC")
    fun forEnsemble(ensembleId: Long): Flow<List<Setlist>>

    @Query("SELECT * FROM setlist WHERE id = :id")
    fun observe(id: Long): Flow<Setlist?>

    @Insert
    suspend fun add(setlist: Setlist): Long

    @Query("UPDATE setlist SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM setlist WHERE id = :id")
    suspend fun remove(id: Long)

    @Query(
        "SELECT e.fileId AS fileId, e.position AS position, " +
            "f.ensembleId AS file_ensembleId, f.fileId AS file_fileId, f.resourceKey AS file_resourceKey, f.name AS file_name, " +
            "f.folders AS file_folders, f.md5 AS file_md5, f.size AS file_size, f.localMd5 AS file_localMd5, " +
            "f.seenMd5 AS file_seenMd5, f.removed AS file_removed, f.addedAt AS file_addedAt, f.mine AS file_mine, " +
            "f.decided AS file_decided, f.previousMd5 AS file_previousMd5, f.imported AS file_imported " +
            "FROM setlist_entry e JOIN setlist s ON s.id = e.setlistId " +
            "LEFT JOIN ensemble_file f ON f.ensembleId = s.ensembleId AND f.fileId = e.fileId " +
            "WHERE e.setlistId = :setlistId ORDER BY e.position",
    )
    fun pieces(setlistId: Long): Flow<List<SetlistPiece>>

    @Query("SELECT fileId FROM setlist_entry WHERE setlistId = :setlistId ORDER BY position")
    suspend fun fileIds(setlistId: Long): List<String>

    @Query("DELETE FROM setlist_entry WHERE setlistId = :setlistId")
    suspend fun clear(setlistId: Long)

    /** [fileId] out of every setlist of ensemble [ensembleId]: it was deleted. */
    @Query("DELETE FROM setlist_entry WHERE fileId = :fileId AND setlistId IN (SELECT id FROM setlist WHERE ensembleId = :ensembleId)")
    suspend fun removeEverywhere(ensembleId: Long, fileId: String)

    @Insert
    suspend fun addEntries(entries: List<SetlistEntry>)

    /** The setlist's pieces, in this order, in one go. */
    @Transaction
    suspend fun setPieces(setlistId: Long, fileIds: List<String>) {
        clear(setlistId)
        addEntries(fileIds.distinct().mapIndexed { i, id -> SetlistEntry(setlistId, id, i) })
    }

    // Each change starts from the pieces as stored, inside one transaction: two quick changes can't undo each other.

    /** [fileIds] at the end. */
    @Transaction
    suspend fun addPieces(setlistId: Long, fileIds: List<String>) = setPieces(setlistId, fileIds(setlistId) + fileIds)

    @Transaction
    suspend fun removePiece(setlistId: Long, fileId: String) = setPieces(setlistId, fileIds(setlistId) - fileId)

    /**
     * The stored pieces put in [order]: only reordered, never added to or taken from. One not in [order] (added
     * meanwhile) keeps its place at the end.
     */
    @Transaction
    suspend fun reorder(setlistId: Long, order: List<String>) {
        val now = fileIds(setlistId)
        setPieces(setlistId, order.filter { it in now } + now.filter { it !in order })
    }
}

/** [items] with the one at [from] moved to [to], the rest keeping their order. Out of range: unchanged. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

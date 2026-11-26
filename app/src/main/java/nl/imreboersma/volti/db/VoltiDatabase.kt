package nl.imreboersma.volti.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import nl.imreboersma.volti.annotate.AnnotationDao
import nl.imreboersma.volti.annotate.AnnotationStroke
import nl.imreboersma.volti.annotate.AnnotationSymbol

/** An orchestra's shared Drive folder, and what I play in it. */
@Entity(tableName = "ensemble", indices = [Index(value = ["folderId"], unique = true)])
data class Ensemble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instrument: String,
    val folderId: String,
    val resourceKey: String? = null,
    /** When the last sync finished; null until the first. */
    val lastSynced: Long? = null,
    /** When I last left the ensemble's screen: files added after that are new. */
    val lookedAt: Long? = null,
)

/** How many files are new in an ensemble: added since I last looked. */
data class NewFiles(val ensembleId: Long, val count: Int)

/**
 * A PDF in an ensemble's Drive folder, and which versions of it are on the tablet. The PDFs themselves are stored by
 * checksum (see PdfStore), so a version is its md5. Kept on the tablet: [localMd5], and [seenMd5] until I open the
 * newer one.
 */
@Entity(
    tableName = "ensemble_file",
    primaryKeys = ["ensembleId", "fileId"],
    foreignKeys = [
        ForeignKey(entity = Ensemble::class, parentColumns = ["id"], childColumns = ["ensembleId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class EnsembleFile(
    val ensembleId: Long,
    val fileId: String,
    val resourceKey: String? = null,
    val name: String,
    /** The subfolders on the way to it, from the ensemble's folder down; empty at the top. */
    val folders: List<String>,
    /** The version in Drive. */
    val md5: String,
    val size: Long,
    /** The newest version on the tablet; null until the first download. Behind [md5] until the new one is in. */
    val localMd5: String? = null,
    /** The version I last opened; null if I never did. When it isn't [localMd5], the score changed since. */
    val seenMd5: String? = null,
    /** Gone from Drive, but I had opened it or marked it mine: kept, so it can still be played from. */
    val removed: Boolean = false,
    /** When a sync added it; null for what the first sync of the ensemble found, which is not new. */
    val addedAt: Long? = null,
    /** I play this one. */
    @ColumnInfo(defaultValue = "0") val mine: Boolean = false,
    /** I said whether it is mine (marked it, or turned down the suggestion): it isn't suggested again. */
    @ColumnInfo(defaultValue = "0") val decided: Boolean = false,
    /**
     * The version I had seen before I opened a newer one: kept, to compare my marks against, until I say they are
     * checked. Null when there is nothing to check.
     */
    val previousMd5: String? = null,
) {
    val changed get() = seenMd5 != null && localMd5 != null && seenMd5 != localMd5
}

class Converters {
    @TypeConverter
    fun fromFolders(folders: List<String>): String = Json.encodeToString(folders)

    @TypeConverter
    fun toFolders(json: String): List<String> = Json.decodeFromString(json)
}

@Dao
interface EnsembleDao {
    @Query("SELECT * FROM ensemble ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<Ensemble>>

    @Query("SELECT * FROM ensemble WHERE id = :id")
    suspend fun get(id: Long): Ensemble?

    @Query("SELECT * FROM ensemble WHERE id = :id")
    fun observe(id: Long): Flow<Ensemble?>

    @Query("UPDATE ensemble SET lastSynced = :time WHERE id = :id")
    suspend fun synced(id: Long, time: Long)

    @Query("UPDATE ensemble SET lookedAt = :time WHERE id = :id")
    suspend fun looked(id: Long, time: Long)

    @Query("UPDATE ensemble SET name = :name, instrument = :instrument WHERE id = :id")
    suspend fun rename(id: Long, name: String, instrument: String)

    @Query(
        "SELECT f.ensembleId AS ensembleId, COUNT(*) AS count FROM ensemble_file f JOIN ensemble e ON e.id = f.ensembleId " +
            "WHERE f.addedAt IS NOT NULL AND (e.lookedAt IS NULL OR f.addedAt > e.lookedAt) GROUP BY f.ensembleId",
    )
    fun newFiles(): Flow<List<NewFiles>>

    /** Throws [android.database.sqlite.SQLiteConstraintException] when the folder is already an ensemble. */
    @Insert
    suspend fun add(ensemble: Ensemble): Long

    @Delete
    suspend fun remove(ensemble: Ensemble)
}

@Dao
interface EnsembleFileDao {
    @Query("SELECT * FROM ensemble_file WHERE ensembleId = :ensembleId")
    fun shown(ensembleId: Long): Flow<List<EnsembleFile>>

    @Query("SELECT * FROM ensemble_file WHERE ensembleId = :ensembleId")
    suspend fun all(ensembleId: Long): List<EnsembleFile>

    @Upsert
    suspend fun save(files: List<EnsembleFile>)

    @Query("DELETE FROM ensemble_file WHERE ensembleId = :ensembleId")
    suspend fun clear(ensembleId: Long)

    /** Marks [fileIds] as mine or not, and as decided: they aren't suggested again. */
    @Query("UPDATE ensemble_file SET mine = :mine, decided = 1 WHERE ensembleId = :ensembleId AND fileId IN (:fileIds)")
    suspend fun setMine(ensembleId: Long, fileIds: List<String>, mine: Boolean)

    /**
     * I opened version [md5]: it is kept until I open a newer one. Opening a newer one keeps the one I saw as
     * [EnsembleFile.previousMd5]; opening it again, or after a change I haven't checked, keeps the one already there.
     * A version I already saw through another ensemble (the same Drive file in two) is no change.
     */
    @Query(
        "UPDATE ensemble_file SET previousMd5 = CASE WHEN previousMd5 IS NULL AND seenMd5 IS NOT NULL AND seenMd5 != :md5 " +
            "AND NOT EXISTS (SELECT 1 FROM ensemble_file o WHERE o.fileId = :fileId AND o.seenMd5 = :md5) " +
            "THEN seenMd5 ELSE previousMd5 END, seenMd5 = :md5 WHERE ensembleId = :ensembleId AND fileId = :fileId",
    )
    suspend fun opened(ensembleId: Long, fileId: String, md5: String)

    /** The previous version of the Drive file [fileId], while there is one to compare against. */
    @Query("SELECT previousMd5 FROM ensemble_file WHERE fileId = :fileId AND previousMd5 IS NOT NULL LIMIT 1")
    suspend fun previousOf(fileId: String): String?

    /** Nothing to compare any more: the previous version can go. */
    @Query("UPDATE ensemble_file SET previousMd5 = NULL WHERE fileId = :fileId")
    suspend fun checked(fileId: String)

    @Query("UPDATE ensemble_file SET localMd5 = :md5 WHERE ensembleId = :ensembleId AND fileId = :fileId AND md5 = :md5")
    suspend fun downloaded(ensembleId: Long, fileId: String, md5: String)

    /** Neither mine nor opened: not kept on the device. It is downloaded again when I open it. */
    @Query("UPDATE ensemble_file SET localMd5 = NULL WHERE ensembleId = :ensembleId AND mine = 0 AND seenMd5 IS NULL")
    suspend fun forgetUnused(ensembleId: Long)

    /** Every version some row still needs on the tablet. */
    @Query(
        "SELECT localMd5 FROM ensemble_file WHERE localMd5 IS NOT NULL UNION SELECT seenMd5 FROM ensemble_file WHERE seenMd5 IS NOT NULL " +
            "UNION SELECT previousMd5 FROM ensemble_file WHERE previousMd5 IS NOT NULL",
    )
    suspend fun versionsInUse(): List<String>

    /** One sync's result in one go, rows it drops included: a half-written list would lose files. */
    @Transaction
    suspend fun replace(ensembleId: Long, update: (List<EnsembleFile>) -> List<EnsembleFile>) {
        val updated = update(all(ensembleId))
        clear(ensembleId)
        save(updated)
    }
}

@Database(
    entities = [Ensemble::class, EnsembleFile::class, AnnotationStroke::class, AnnotationSymbol::class],
    version = 7,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
@TypeConverters(Converters::class)
abstract class VoltiDatabase : RoomDatabase() {
    abstract fun ensembles(): EnsembleDao

    abstract fun files(): EnsembleFileDao

    abstract fun annotations(): AnnotationDao

    companion object {
        @Volatile
        private var instance: VoltiDatabase? = null

        fun get(context: Context): VoltiDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, VoltiDatabase::class.java, "volti.db")
                .build()
                .also { instance = it }
        }
    }
}

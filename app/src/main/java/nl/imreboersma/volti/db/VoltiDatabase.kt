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
import androidx.room.migration.AutoMigrationSpec
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import nl.imreboersma.volti.annotate.AnnotationDao
import nl.imreboersma.volti.annotate.AnnotationStroke
import nl.imreboersma.volti.annotate.AnnotationSymbol
import nl.imreboersma.volti.setlist.Setlist
import nl.imreboersma.volti.setlist.SetlistDao
import nl.imreboersma.volti.setlist.SetlistEntry

/**
 * An orchestra, and what I play in it. Its files come from its shared Drive folder ([folderId]), from imports, or
 * both; without a folder, only from imports.
 */
@Entity(tableName = "ensemble", indices = [Index(value = ["folderId"], unique = true)])
data class Ensemble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instrument: String,
    val folderId: String? = null,
    val resourceKey: String? = null,
    /** When the last sync finished; null until the first. */
    val lastSynced: Long? = null,
    /** When I last left the ensemble's screen: files added after that are new. */
    val lookedAt: Long? = null,
)

/** How many files are new in an ensemble: added since I last looked. */
data class NewFiles(val ensembleId: Long, val count: Int)

/**
 * A PDF in an ensemble, from its Drive folder or imported, and which versions of it are on the tablet. The PDFs themselves are stored by
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
    /** Imported from the device or another app, not from Drive: [fileId] is Volti's own, and a sync leaves it alone. */
    @ColumnInfo(defaultValue = "0") val imported: Boolean = false,
    /**
     * My own name for it ("Bizet - Farandole - F Horn 1"); null: its file name. The file in Drive keeps its name. A
     * copy of the [FileName] for this file, which outlives this row.
     */
    val displayName: String? = null,
) {
    /** The name it shows by: mine, else the file's. */
    val shownName get() = displayName ?: name

    val changed get() = seenMd5 != null && localMd5 != null && seenMd5 != localMd5

    /** What its annotations belong to: the file, so they stay with it through new versions. */
    val scoreKey get() = fileKey(fileId)
}

/**
 * My name for a file, by its [fileKey] as its marks are: it outlives the ensemble's rows, so the same Drive file in
 * another ensemble, or in this one added again, has it too.
 */
@Entity(tableName = "file_name")
data class FileName(@PrimaryKey val fileKey: String, val name: String)

/** The annotation key of an ensemble's file [fileId]. */
fun fileKey(fileId: String) = "file:$fileId"

/** The annotation key of a local file with content [md5]: a rename keeps its marks, an edited PDF starts clean. */
fun localKey(md5: String) = "local:$md5"

/** The ensemble file an annotation key [key] belongs to; null for a local score's. */
fun fileIdOf(key: String): String? = key.removePrefix("file:").takeIf { key.startsWith("file:") }

/**
 * Annotation keys said `drive:` while every ensemble file came from Drive; now that imports are ensemble files too,
 * `file:`.
 */
class DriveKeysToFileKeys : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        listOf("annotation_stroke", "annotation_symbol").forEach { table ->
            db.execSQL("UPDATE $table SET scoreKey = 'file:' || substr(scoreKey, 7) WHERE scoreKey LIKE 'drive:%'")
        }
    }
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

    /** The ensembles holding parts I imported: removing one deletes those for good. */
    @Query("SELECT DISTINCT ensembleId FROM ensemble_file WHERE imported = 1")
    fun withImports(): Flow<List<Long>>

    /** The files of every ensemble: to search them all, and for my parts from all of them. */
    @Query("SELECT * FROM ensemble_file")
    fun everywhere(): Flow<List<EnsembleFile>>

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

    /** From Drive, neither mine nor opened: not kept on the device. It is downloaded again when I open it. */
    @Query("UPDATE ensemble_file SET localMd5 = NULL WHERE ensembleId = :ensembleId AND mine = 0 AND seenMd5 IS NULL AND imported = 0")
    suspend fun forgetUnused(ensembleId: Long)

    @Query("DELETE FROM ensemble_file WHERE ensembleId = :ensembleId AND fileId = :fileId")
    suspend fun remove(ensembleId: Long, fileId: String)

    @Query("UPDATE ensemble_file SET displayName = :name WHERE fileId = :fileId")
    suspend fun showName(fileId: String, name: String?)

    @Upsert
    suspend fun keepName(name: FileName)

    @Query("DELETE FROM file_name WHERE fileKey = :fileKey")
    suspend fun forgetName(fileKey: String)

    /** Every row of ensemble [ensembleId] with my name for its file: rows a sync just made too. */
    @Query(
        "UPDATE ensemble_file SET displayName = (SELECT name FROM file_name WHERE fileKey = 'file:' || ensemble_file.fileId) " +
            "WHERE ensembleId = :ensembleId",
    )
    suspend fun applyNames(ensembleId: Long)

    /** My name for file [fileId], in every ensemble it is in, as its marks are; null goes back to its file name. */
    @Transaction
    suspend fun rename(fileId: String, name: String?) {
        if (name == null) forgetName(fileKey(fileId)) else keepName(FileName(fileKey(fileId), name))
        showName(fileId, name)
    }

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
        applyNames(ensembleId)
    }
}

@Database(
    entities = [
        Ensemble::class, EnsembleFile::class, FileName::class, AnnotationStroke::class, AnnotationSymbol::class,
        Setlist::class, SetlistEntry::class,
    ],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = DriveKeysToFileKeys::class),
        AutoMigration(from = 9, to = 10),
    ],
)
@TypeConverters(Converters::class)
abstract class VoltiDatabase : RoomDatabase() {
    abstract fun ensembles(): EnsembleDao

    abstract fun files(): EnsembleFileDao

    abstract fun annotations(): AnnotationDao

    abstract fun setlists(): SetlistDao

    /** An imported file, with its places in setlists and its marks, in one go: nothing of it is left half. */
    suspend fun deleteImported(file: EnsembleFile) = withTransaction {
        files().remove(file.ensembleId, file.fileId)
        setlists().removeEverywhere(file.ensembleId, file.fileId)
        annotations().removeScore(file.scoreKey)
        files().forgetName(file.scoreKey)
    }

    /**
     * An ensemble goes, with its files. Its imports can't come back (imported again, a PDF gets a new id), so their
     * marks and names go too; a Drive part's stay, for when its folder is added again.
     */
    suspend fun removeEnsemble(ensemble: Ensemble) = withTransaction {
        files().all(ensemble.id).filter { it.imported }.forEach {
            annotations().removeScore(it.scoreKey)
            files().forgetName(it.scoreKey)
        }
        ensembles().remove(ensemble)
    }

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

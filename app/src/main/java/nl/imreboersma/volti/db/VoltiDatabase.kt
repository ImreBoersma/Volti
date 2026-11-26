package nl.imreboersma.volti.db

import android.content.Context
import androidx.room.AutoMigration
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

/** An orchestra's shared Drive folder, and what I play in it. */
@Entity(tableName = "ensemble", indices = [Index(value = ["folderId"], unique = true)])
data class Ensemble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instrument: String,
    val folderId: String,
    val resourceKey: String? = null,
)

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
    /** Gone from Drive, but I had opened it: kept, so it can still be played from. */
    val removed: Boolean = false,
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

    /** I opened version [md5]: it is kept until I open a newer one. */
    @Query("UPDATE ensemble_file SET seenMd5 = :md5 WHERE ensembleId = :ensembleId AND fileId = :fileId")
    suspend fun opened(ensembleId: Long, fileId: String, md5: String)

    @Query("UPDATE ensemble_file SET localMd5 = :md5 WHERE ensembleId = :ensembleId AND fileId = :fileId AND md5 = :md5")
    suspend fun downloaded(ensembleId: Long, fileId: String, md5: String)

    /** Every version some row still needs on the tablet. */
    @Query("SELECT localMd5 FROM ensemble_file WHERE localMd5 IS NOT NULL UNION SELECT seenMd5 FROM ensemble_file WHERE seenMd5 IS NOT NULL")
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
    entities = [Ensemble::class, EnsembleFile::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class VoltiDatabase : RoomDatabase() {
    abstract fun ensembles(): EnsembleDao

    abstract fun files(): EnsembleFileDao

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

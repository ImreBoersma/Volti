package nl.imreboersma.volti.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** An orchestra's shared Drive folder, and what I play in it. */
@Entity(tableName = "ensemble", indices = [Index(value = ["folderId"], unique = true)])
data class Ensemble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instrument: String,
    val folderId: String,
    val resourceKey: String? = null,
)

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

@Database(entities = [Ensemble::class], version = 1)
abstract class VoltiDatabase : RoomDatabase() {
    abstract fun ensembles(): EnsembleDao

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

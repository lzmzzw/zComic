package app.zcomic.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "volumes")
data class VolumeRecord(
    @PrimaryKey val id: String,
    val comicId: String,
    val comicTitle: String,
    val title: String,
    val number: Int,
    val uri: String,
    val coverUri: String = "",
    val page: Int = 0,
    val pageCount: Int = 0,
    val lastReadAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val contentHash: String = "",
    @ColumnInfo(defaultValue = "''") val sourceId: String = ""
)

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val id: String,
    val comicId: String,
    val comicTitle: String,
    val volumeTitle: String,
    val number: Int,
    val detailUrl: String,
    val status: String = "queued",
    val received: Long = 0,
    val total: Long = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ComicDao {
    @Query("SELECT * FROM volumes ORDER BY lastReadAt DESC, addedAt DESC")
    fun volumes(): Flow<List<VolumeRecord>>
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun downloads(): Flow<List<DownloadRecord>>
    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    fun observeDownload(id: String): Flow<DownloadRecord?>
    @Query("SELECT * FROM volumes WHERE id = :id OR sourceId = :id LIMIT 1")
    suspend fun volume(id: String): VolumeRecord?
    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun download(id: String): DownloadRecord?
    @Query("SELECT * FROM downloads WHERE status IN ('queued','running') ORDER BY createdAt")
    suspend fun pendingDownloads(): List<DownloadRecord>
    @Query("SELECT * FROM volumes")
    suspend fun allVolumes(): List<VolumeRecord>
    @Query("SELECT * FROM volumes WHERE id = :hash OR contentHash = :hash OR uri = :uri LIMIT 1")
    suspend fun findVolume(hash: String, uri: String): VolumeRecord?
    @Query("UPDATE volumes SET contentHash = :hash WHERE id = :id")
    suspend fun updateContentHash(id: String, hash: String)
    @Query("UPDATE volumes SET sourceId = :sourceId WHERE id = :id")
    suspend fun associateSource(id: String, sourceId: String)
    @Query("UPDATE volumes SET page = :page, pageCount = :count, lastReadAt = :readAt WHERE id = :id")
    suspend fun updateReading(id: String, page: Int, count: Int, readAt: Long)
    @Query("UPDATE downloads SET received = :received, total = :total, error = '' WHERE id = :id AND status = 'running'")
    suspend fun updateProgress(id: String, received: Long, total: Long)
    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id AND status IN ('queued', 'running', 'paused', 'failed')")
    suspend fun updateDownloadState(id: String, status: String, error: String = "")
    @Query("UPDATE downloads SET status = :status WHERE id = :id AND status IN ('running', 'queued')")
    suspend fun interruptDownload(id: String, status: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVolume(value: VolumeRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDownload(value: DownloadRecord)
    @Query("DELETE FROM volumes WHERE id = :id")
    suspend fun deleteVolume(id: String)
    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: String)
    @Query("DELETE FROM downloads WHERE status = 'completed'")
    suspend fun clearCompleted()
}

@Database(entities = [VolumeRecord::class, DownloadRecord::class], version = 2, exportSchema = true)
abstract class ComicDatabase : RoomDatabase() {
    abstract fun dao(): ComicDao
    companion object {
        @Volatile private var instance: ComicDatabase? = null
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE volumes ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE volumes ADD COLUMN sourceId TEXT NOT NULL DEFAULT ''")
            }
        }

        fun open(context: Context): ComicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ComicDatabase::class.java, "zcomic.db"
            ).addMigrations(migration1To2).build().also { instance = it }
        }
    }
}

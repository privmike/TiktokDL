package com.privmike.tiktokdl.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_videos")
data class SavedVideo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val videoUrl: String,
    val collectionName: String,
    val savedAtTimestamp: Long = System.currentTimeMillis(),
    var partNumber : Int,
    var localVideoPath: String? = null,
    var srtFilePath : String? = null
)

@Entity(tableName = "video_collections")
data class VideoCollection(
    @PrimaryKey val name: String, // The name is the unique ID
    val createdAt: Long = System.currentTimeMillis()
)



@Dao
interface VideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: SavedVideo): Long

    @Update
    suspend fun updateVideo(video: SavedVideo)

    @Query("SELECT * FROM saved_videos WHERE collectionName = :collection")
    suspend fun getVideosByCollection(collection: String): List<SavedVideo>

    @Query("SELECT * FROM saved_videos WHERE collectionName = :collectionName")
    fun getVideosForCollection(collectionName: String): Flow<List<SavedVideo>>

    @Query("SELECT IFNULL(MAX(partNumber), 0) FROM saved_videos WHERE collectionName = :collectionName")
    suspend fun getMaxPartNumberForCollection(collectionName: String): Int
}

@Dao
interface CollectionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollection(collection: VideoCollection)

    // Notice we return Flow<List<String>> to perfectly match your existing UI code!
    @Query("SELECT name FROM video_collections ORDER BY createdAt DESC")
    fun getAllCollections(): Flow<List<String>>
}


@Database(entities = [SavedVideo::class, VideoCollection::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun collectionDao(): CollectionDao // NEW!

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tiktok_gallery_database"
                )
                    // 🚨 IMPORTANT: This prevents a crash when adding a new table!
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
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

// 1. The Table
@Entity(tableName = "saved_videos")
data class SavedVideo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val videoUrl: String,
    val collectionName: String,
    val savedAtTimestamp: Long = System.currentTimeMillis(),
    var partNumber : Int,
    var localVideoPath: String? = null, // ADDED: To store your renamed MP4 path!
    var srtFilePath : String? = null
)

// 2. The Queries
@Dao
interface VideoDao {
    // FIXED: Return Long so you can get the generated ID!
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: SavedVideo): Long

    // ADDED: The Update function!
    @Update
    suspend fun updateVideo(video: SavedVideo)

    @Query("SELECT DISTINCT collectionName FROM saved_videos")
    fun getAllCollections(): Flow<List<String>>

    @Query("SELECT * FROM saved_videos WHERE collectionName = :collection")
    suspend fun getVideosByCollection(collection: String): List<SavedVideo>

    @Query("SELECT * FROM saved_videos WHERE collectionName = :collectionName")
    fun getVideosForCollection(collectionName: String): Flow<List<SavedVideo>>

    @Query("SELECT IFNULL(MAX(partNumber), 0) FROM saved_videos WHERE collectionName = :collectionName")
    suspend fun getMaxPartNumberForCollection(collectionName: String): Int
}

// 3. The Database Connection
@Database(entities = [SavedVideo::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tiktok_gallery_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
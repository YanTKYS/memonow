package com.yantkys.memonow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    @Query("SELECT * FROM posts ORDER BY createdAt ASC, sequence ASC")
    fun observeAll(): Flow<List<Post>>

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun find(id: String): Post?

    @Query("UPDATE posts SET placeName = :name WHERE id = :id")
    suspend fun attachPlace(id: String, name: String): Int

    @Query("UPDATE posts SET weatherCode = :code, temperatureC = :temperature, weatherAt = :at WHERE id = :id")
    suspend fun attachWeather(id: String, code: Int?, temperature: Double?, at: Long): Int

    @Insert
    suspend fun insert(post: Post)

    @Query("UPDATE posts SET body = :body WHERE id = :id")
    suspend fun updateBody(id: String, body: String): Int

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: String): Int
}

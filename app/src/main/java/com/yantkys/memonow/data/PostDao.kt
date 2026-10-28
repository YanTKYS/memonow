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

    @Query("UPDATE posts SET placeName = :name WHERE id = :id AND placeName IS NULL")
    suspend fun attachPlace(id: String, name: String): Int

    @Query("UPDATE posts SET weatherCode = :code, temperatureC = :temperature, weatherAt = :at WHERE id = :id AND weatherAt IS NULL")
    suspend fun attachWeather(id: String, code: Int?, temperature: Double?, at: Long): Int

    @Query("SELECT COALESCE(SUM(LENGTH(body) + COALESCE(LENGTH(placeName), 0)), 0) FROM posts")
    suspend fun textLength(): Long

    @Query("SELECT COUNT(*) FROM posts")
    suspend fun count(): Int

    @Query("SELECT * FROM posts ORDER BY createdAt ASC, sequence ASC")
    suspend fun snapshot(): List<Post>

    @Query("DELETE FROM posts")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(posts: List<Post>)

    @Insert
    suspend fun insert(post: Post)

    @Query("UPDATE posts SET body = :body WHERE id = :id")
    suspend fun updateBody(id: String, body: String): Int

    @Query("UPDATE posts SET body = :body, moodId = :moodId WHERE id = :id")
    suspend fun updateContent(id: String, body: String, moodId: String?): Int

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: String): Int
}

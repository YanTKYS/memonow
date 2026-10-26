package com.yantkys.memonow.data

import com.yantkys.memonow.backup.InvalidBackup
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PostRepository(private val dao: PostDao, private val clock: Clock = Clock.systemUTC()) {
    val posts = dao.observeAll()
    private val writes = Mutex()
    private var revision = 0L
    private var metadataGeneration = 0L

    suspend fun create(body: String): String = writes.withLock {
        val id = UUID.randomUUID().toString()
        dao.insert(Post(id = id, body = validBody(body), createdAt = clock.millis()))
        revision++
        id
    }
    suspend fun find(id: String) = dao.find(id)
    suspend fun metadataToken(): Long = writes.withLock { metadataGeneration }
    suspend fun attachPlace(id: String, name: String, token: Long): Int = writes.withLock {
        if (token != metadataGeneration) return@withLock 0
        dao.attachPlace(id, name).also { if (it > 0) revision++ }
    }
    suspend fun attachWeather(id: String, code: Int?, temperature: Double?, at: Long, token: Long): Int = writes.withLock {
        if (token != metadataGeneration) return@withLock 0
        dao.attachWeather(id, code, temperature, at).also { if (it > 0) revision++ }
    }
    suspend fun edit(id: String, body: String) = writes.withLock {
        check(dao.updateBody(id, validBody(body)) == 1) { "Post no longer exists" }
        revision++
    }
    suspend fun delete(id: String) = writes.withLock {
        if (dao.delete(id) > 0) revision++
    }
    internal suspend fun <T> exclusiveSnapshot(block: suspend () -> T): T = writes.withLock { block() }
    internal suspend fun <T> prepareReplacement(block: suspend (Long) -> T): T = writes.withLock {
        metadataGeneration++ // Fence even callbacks which ignore job cancellation.
        block(revision)
    }
    internal suspend fun replaceExclusively(expectedRevision: Long, block: suspend () -> Unit) = writes.withLock {
        metadataGeneration++
        if (revision != expectedRevision) throw InvalidBackup("記録が変更されました。ファイルを選び直してください。")
        block()
        revision++
    }
    private fun validBody(body: String): String = body.trim().also {
        require(it.isNotBlank()) { "Post must not be blank" }
    }
}

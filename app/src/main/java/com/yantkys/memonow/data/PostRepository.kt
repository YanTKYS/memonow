package com.yantkys.memonow.data

import java.time.Clock
import java.util.UUID

class PostRepository(private val dao: PostDao, private val clock: Clock = Clock.systemUTC()) {
    val posts = dao.observeAll()

    suspend fun create(body: String): String {
        val id = UUID.randomUUID().toString()
        dao.insert(Post(id = id, body = validBody(body), createdAt = clock.millis()))
        return id
    }

    suspend fun edit(id: String, body: String) {
        check(dao.updateBody(id, validBody(body)) == 1) { "Post no longer exists" }
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }

    private fun validBody(body: String): String = body.trim().also {
        require(it.isNotBlank()) { "Post must not be blank" }
    }
}

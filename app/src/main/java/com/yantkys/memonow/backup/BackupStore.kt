package com.yantkys.memonow.backup

import androidx.room.withTransaction
import com.yantkys.memonow.data.MemonowDatabase
import com.yantkys.memonow.data.PostRepository
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RestorePlan internal constructor(val document: BackupDocument, val existingCount: Int, internal val revision: Long)
class BackupStore(private val db: MemonowDatabase, private val repository: PostRepository, private val clock: Clock = Clock.systemUTC()) {
    suspend fun export(): Pair<Int, ByteArray> {
        val posts = repository.exclusiveSnapshot {
            if (db.posts().textLength() > BackupJson.MAX_BYTES) throw InvalidBackup("ファイルは10MiBまでです。")
            if (db.posts().count() > BackupJson.MAX_POSTS) throw InvalidBackup("投稿は1万件までです。")
            db.posts().snapshot()
        }
        return posts.size to withContext(Dispatchers.Default) { BackupJson.encode(BackupDocument(clock.millis(), posts)) }
    }

    suspend fun prepare(document: BackupDocument): RestorePlan {
        withContext(Dispatchers.Default) { BackupJson.validate(document) }
        return repository.prepareReplacement { revision ->
        val count = db.posts().count()
        rejectEmpty(count, document)
        RestorePlan(document, count, revision)
        }
    }

    suspend fun restore(plan: RestorePlan) = repository.replaceExclusively(plan.revision) {
        db.withTransaction {
            val dao = db.posts()
            if (dao.count() != plan.existingCount) throw InvalidBackup("記録が変更されました。ファイルを選び直してください。")
            rejectEmpty(dao.count(), plan.document)
            dao.deleteAll()
            // A previous larger AUTOINCREMENT counter must not affect restored ordering.
            db.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence WHERE name = 'posts'")
            dao.insertAll(plan.document.posts)
        }
    }

    private fun rejectEmpty(count: Int, document: BackupDocument) {
        if (count > 0 && document.posts.isEmpty()) throw InvalidBackup("空のバックアップで既存の記録を置き換えることはできません。")
    }
}

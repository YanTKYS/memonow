package com.yantkys.memonow

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.data.MemonowDatabase
import com.yantkys.memonow.data.Post
import com.yantkys.memonow.data.PostRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class MoodMigrationTest {
    @Test fun bothHistoricalSchemasMigrateToThreeWithoutLosingPostsOrAttributes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (version in listOf(1, 2)) {
            val name = "mood-migration-$version.db"
            context.deleteDatabase(name)
            val original = listOf(3L, 7L).map { sequence ->
                Post(sequence, UUID.randomUUID().toString(), "旧投稿\n🌤 $sequence", 1000,
                    placeName = if (version == 2) "沖縄県 糸満市" else null,
                    weatherCode = if (version == 2) 0 else null,
                    temperatureC = if (version == 2) 0.0 else null,
                    weatherAt = if (version == 2) 1000 else null)
            }
            val schema = javaClass.classLoader!!.getResourceAsStream("com.yantkys.memonow.data.MemonowDatabase/$version.json")!!
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val entity = schema.getJSONArray("entities").getJSONObject(0)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "posts"))
                val indexes = entity.getJSONArray("indices")
                for (i in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "posts"))
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                for (post in original) {
                    if (version == 1) old.execSQL("INSERT INTO posts(sequence,id,body,createdAt) VALUES (?,?,?,?)",
                        arrayOf<Any?>(post.sequence, post.id, post.body, post.createdAt))
                    else old.execSQL("INSERT INTO posts(sequence,id,body,createdAt,placeName,weatherCode,temperatureC,weatherAt) VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(post.sequence, post.id, post.body, post.createdAt, post.placeName, post.weatherCode, post.temperatureC, post.weatherAt))
                }
                old.version = version
            }
            fun open() = Room.databaseBuilder(context, MemonowDatabase::class.java, name)
                .addMigrations(MemonowDatabase.MIGRATION_1_2, MemonowDatabase.MIGRATION_2_3).build()
            var migrated = open()
            try {
                assertEquals(original, migrated.posts().snapshot())
                assertEquals(3, migrated.openHelper.writableDatabase.version)
                migrated.close()
                migrated = open()
                assertEquals(original, migrated.posts().snapshot())
                val repo = PostRepository(migrated.posts())
                val id = repo.create("移行後の記録", "fun")
                assertTrue(repo.find(id)!!.sequence > 7)
                assertEquals("fun", repo.find(id)!!.moodId)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }
    }
}

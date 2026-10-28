package com.yantkys.memonow.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Post::class], version = 3, exportSchema = true)
abstract class MemonowDatabase : RoomDatabase() {
    abstract fun posts(): PostDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE posts ADD COLUMN moodId TEXT")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE posts ADD COLUMN placeName TEXT")
                db.execSQL("ALTER TABLE posts ADD COLUMN weatherCode INTEGER")
                db.execSQL("ALTER TABLE posts ADD COLUMN temperatureC REAL")
                db.execSQL("ALTER TABLE posts ADD COLUMN weatherAt INTEGER")
            }
        }
    }
}

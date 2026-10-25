package com.yantkys.memonow.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Post::class], version = 1, exportSchema = true)
abstract class MemonowDatabase : RoomDatabase() {
    abstract fun posts(): PostDao
}

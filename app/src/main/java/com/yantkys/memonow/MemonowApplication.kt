package com.yantkys.memonow

import android.app.Application
import androidx.room.Room
import com.yantkys.memonow.data.MemonowDatabase
import com.yantkys.memonow.data.PostRepository

class MemonowApplication : Application() {
    val repository by lazy {
        PostRepository(
            Room.databaseBuilder(this, MemonowDatabase::class.java, "memonow.db").build().posts(),
        )
    }
}

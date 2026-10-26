package com.yantkys.memonow

import android.app.Application
import com.yantkys.memonow.backup.BackupStore
import androidx.room.Room
import com.yantkys.memonow.data.MemonowDatabase
import com.yantkys.memonow.data.PostRepository
import com.yantkys.memonow.metadata.AndroidLocationSource
import com.yantkys.memonow.metadata.AndroidPlaceSource
import com.yantkys.memonow.metadata.MetadataSettings
import com.yantkys.memonow.metadata.OpenMeteoWeather
import com.yantkys.memonow.metadata.PostMetadata

class MemonowApplication : Application() {
    val metadataSettings by lazy { MetadataSettings(this) }
    val locationSource by lazy { AndroidLocationSource(this) }
    private var startedScreens = 0
    fun screenStarted() { startedScreens++ }
    fun screenStopped(): Boolean {
        startedScreens = (startedScreens - 1).coerceAtLeast(0)
        return startedScreens == 0
    }
    var restoring = false
    fun metadataAllowed() = !restoring && startedScreens > 0 && metadataSettings.enabled.value && locationSource.hasPermission()
    val postMetadata by lazy {
        PostMetadata(repository, locationSource, AndroidPlaceSource(this), OpenMeteoWeather(), ::metadataAllowed)
    }

    private val database by lazy {
        Room.databaseBuilder(this, MemonowDatabase::class.java, "memonow.db")
            .addMigrations(MemonowDatabase.MIGRATION_1_2).build()
    }
    val repository by lazy { PostRepository(database.posts()) }
    val backupStore by lazy { BackupStore(database, repository) }
}

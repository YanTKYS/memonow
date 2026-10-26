package com.yantkys.memonow.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "posts",
    indices = [Index(value = ["id"], unique = true), Index(value = ["createdAt", "sequence"])],
)
data class Post(
    // Local sequence breaks timestamp ties without relying on random UUID order.
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val body: String,
    val createdAt: Long, // UTC epoch milliseconds; formatted in the device timezone.
    val placeName: String? = null,
    val weatherCode: Int? = null,
    val temperatureC: Double? = null,
    val weatherAt: Long? = null,
)

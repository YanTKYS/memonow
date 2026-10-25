package com.yantkys.memonow.metadata

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MetadataSettings(context: Context) {
    private val preferences = context.getSharedPreferences("metadata", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    var permissionAsked: Boolean
        get() = preferences.getBoolean("permissionAsked", false)
        set(value) { preferences.edit().putBoolean("permissionAsked", value).apply() }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        preferences.edit().putBoolean("enabled", value).apply()
    }
}

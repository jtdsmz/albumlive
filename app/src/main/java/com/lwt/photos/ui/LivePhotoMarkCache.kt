package com.lwt.photos.ui

import java.util.concurrent.ConcurrentHashMap

object LivePhotoMarkCache {
    private val cache = ConcurrentHashMap<String, Boolean>()

    fun get(path: String): Boolean? {
        if (path.isBlank()) return false
        return cache[path]
    }

    fun put(path: String, isLivePhoto: Boolean) {
        if (path.isNotBlank()) {
            cache[path] = isLivePhoto
        }
    }
}

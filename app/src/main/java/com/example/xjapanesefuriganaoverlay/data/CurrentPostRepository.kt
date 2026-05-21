package com.example.xjapanesefuriganaoverlay.data

import com.example.xjapanesefuriganaoverlay.util.TextHash

data class CurrentPostSnapshot(
    val posts: List<DetectedPost>,
    val version: Long,
    val lastUpdatedAt: Long,
    val lastHash: String?
)

object CurrentPostRepository {
    private val lock = Any()
    private val posts = mutableListOf<DetectedPost>()
    private var version = 0L
    private var lastUpdatedAt = 0L
    private var lastHash: String? = null

    fun replace(newPosts: List<DetectedPost>, postsHash: String = hashPosts(newPosts)): Boolean {
        synchronized(lock) {
            if (lastHash == postsHash) return false
            posts.clear()
            posts.addAll(newPosts)
            lastHash = postsHash
            lastUpdatedAt = System.currentTimeMillis()
            version++
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            if (posts.isEmpty() && lastHash == null) return
            posts.clear()
            lastHash = null
            lastUpdatedAt = System.currentTimeMillis()
            version++
        }
    }

    fun getPosts(): List<DetectedPost> {
        return synchronized(lock) {
            posts.toList()
        }
    }

    fun snapshot(): CurrentPostSnapshot {
        return synchronized(lock) {
            CurrentPostSnapshot(
                posts = posts.toList(),
                version = version,
                lastUpdatedAt = lastUpdatedAt,
                lastHash = lastHash
            )
        }
    }

    private fun hashPosts(value: List<DetectedPost>): String {
        return TextHash.sha256Short(value.joinToString("\n") { "${it.packageName}\t${it.text}" })
    }
}

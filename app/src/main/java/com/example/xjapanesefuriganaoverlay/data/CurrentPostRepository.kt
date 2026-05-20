package com.example.xjapanesefuriganaoverlay.data

object CurrentPostRepository {
    private val lock = Any()
    private val posts = mutableListOf<DetectedPost>()

    fun replace(newPosts: List<DetectedPost>) {
        synchronized(lock) {
            posts.clear()
            posts.addAll(newPosts)
        }
    }

    fun clear() {
        synchronized(lock) {
            posts.clear()
        }
    }

    fun getPosts(): List<DetectedPost> {
        return synchronized(lock) {
            posts.toList()
        }
    }
}

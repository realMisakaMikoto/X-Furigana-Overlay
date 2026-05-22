package com.example.xjapanesefuriganaoverlay.util

import java.security.MessageDigest

object TextHash {
    fun sha256Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}

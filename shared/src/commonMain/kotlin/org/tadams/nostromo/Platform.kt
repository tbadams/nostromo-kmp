package org.tadams.nostromo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
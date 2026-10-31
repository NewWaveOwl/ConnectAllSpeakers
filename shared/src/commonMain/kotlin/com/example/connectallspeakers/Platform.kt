package com.example.connectallspeakers

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
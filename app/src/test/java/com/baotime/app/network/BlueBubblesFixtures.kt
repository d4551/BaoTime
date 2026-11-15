package com.baotime.app.network

/** Loads the redacted BlueBubbles contract fixtures from app/src/test/resources/bluebubbles. */
object BlueBubblesFixtures {
    fun read(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("bluebubbles/$name")) {
            "Missing fixture bluebubbles/$name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiPaw"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("all", "fr", "en").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://server-raspberrypi.tailb8bddd.ts.net"
        }
    }
}

import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiPaw"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("all", "en", "fr", "ja", "zh", "es", "ko", "pt", "ru", "it", "de").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://hentaipaw.com"
        }
    }
}

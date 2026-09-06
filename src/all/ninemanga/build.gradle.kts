import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NineManga"
    versionCode = 27
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        name = "NineMangaEn"
        lang = "en"
        baseUrl = "https://www.ninemanga.com"
    }
    source {
        name = "NineMangaEs"
        lang = "es"
        baseUrl = "https://es.ninemanga.com"
    }
    source {
        name = "NineMangaBr"
        lang = "pt-BR"
        baseUrl = "https://br.ninemanga.com"
        id = 7162569729467394726L
    }
    source {
        name = "NineMangaRu"
        lang = "ru"
        baseUrl = "https://ru.ninemanga.com"
    }
    source {
        name = "NineMangaDe"
        lang = "de"
        baseUrl = "https://de.ninemanga.com"
    }
    source {
        name = "NineMangaIt"
        lang = "it"
        baseUrl = "https://it.ninemanga.com"
    }
    source {
        name = "NineMangaFr"
        lang = "fr"
        baseUrl = "https://fr.ninemanga.com"
    }
}

dependencies {
    implementation(project(":lib:cookieinterceptor"))
}

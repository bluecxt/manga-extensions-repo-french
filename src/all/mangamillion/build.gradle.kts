import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MANGA MILLION by SHUEISHA"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    listOf("en", "fr", "es", "de", "id", "pt-BR", "ru", "th", "vi").forEach {
        source {
            lang = it
            baseUrl = "https://mangamillion.shueisha.co.jp"
        }
    }

    deeplink {
        host("mangamillion.shueisha.co.jp")
        host("www.mangamillion.shueisha.co.jp")
        path("/en/title/..*")
        path("/fr/title/..*")
    }
}

dependencies {
    implementation(project(":lib:i18n"))
}

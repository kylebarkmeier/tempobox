// TempoBox — multi-module Android music player.
// Module graph (all :core modules are reusable libraries; :app is the only Android application):
//
//   :app ──────────────┬────────────────────────────────────────────┐
//                      │                                            │
//   :core:playback ────┤          :core:library ────┐               │
//   :core:scrobble ────┤          :core:playlist ───┤               │
//   :core:artwork ─────┤          :core:tags ───────┼── :core:database
//   :core:settings ────┘                            └── :core:model / :core:common
//
pluginManagement {
    // build-logic hosts our convention plugins (see build-logic/README.md)
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tempobox"

// Reproducible, typesafe module accessors (e.g. projects.core.model)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:settings")
include(":core:tags")
include(":core:playlist")
include(":core:library")
include(":core:playback")
include(":core:scrobble")
include(":core:artwork")

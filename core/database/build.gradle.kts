// Room persistence layer: entities, DAOs, database, and their Hilt bindings.
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
}

android {
    namespace = "com.tempobox.database"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}

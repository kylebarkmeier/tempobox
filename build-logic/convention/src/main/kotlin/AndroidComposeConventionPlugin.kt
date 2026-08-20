import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Adds Jetpack Compose to a module (application or library).
 *
 * Kotlin 2.x moves the Compose compiler into the Kotlin repo, enabled via the
 * `org.jetbrains.kotlin.plugin.compose` plugin — no composeOptions block needed.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            // Enable the compose build feature on whichever Android plugin is present.
            pluginManager.withPlugin("com.android.application") {
                extensions.getByType(ApplicationExtension::class.java).buildFeatures.compose = true
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.getByType(LibraryExtension::class.java).buildFeatures.compose = true
            }

            val bom = libs.findLibrary("androidx-compose-bom").get()
            dependencies {
                add("implementation", platform(bom))
                add("androidTestImplementation", platform(bom))
                add("implementation", libs.findLibrary("androidx-compose-ui").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
                add("implementation", libs.findLibrary("androidx-compose-material3").get())
                add("implementation", libs.findLibrary("androidx-compose-foundation").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            }
        }
    }
}

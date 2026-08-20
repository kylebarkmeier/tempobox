import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention for every :core:* Android library module.
 *
 * A module applying `tempobox.android.library` only has to declare its
 * namespace and dependencies.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")

            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                // Libraries should not pin a targetSdk (deprecated for libraries);
                // consumer (the app) decides.
            }

            addCommonTestDependencies()
        }
    }
}

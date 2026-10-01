import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention for the single :app module.
 *
 * Applies the Android application plugin (AGP 9 built-in Kotlin — no separate
 * kotlin-android plugin) and the shared Android configuration (SDK levels,
 * JVM target, test options).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = libs.versionInt("targetSdk")
            }

            addCommonTestDependencies()
            addAndroidUnitTestDependencies()
        }
    }
}

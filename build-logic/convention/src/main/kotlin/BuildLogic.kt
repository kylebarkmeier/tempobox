import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Shared helpers used by every TempoBox convention plugin.
 *
 * Keeping this logic in one place means each module's build.gradle.kts stays a
 * short declaration of *what* the module is, not *how* it is compiled.
 */

/** Typed access to gradle/libs.versions.toml from within a convention plugin. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.versionInt(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()

/**
 * Baseline Android configuration shared by the application module and every
 * Android library module: SDK levels, Java/Kotlin targets, and unit-test options.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension<*, *, *, *, *, *>) {
    extension.apply {
        compileSdk = libs.versionInt("compileSdk")

        defaultConfig {
            minSdk = libs.versionInt("minSdk")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions {
            unitTests {
                // Robolectric needs Android resources; harmless for plain JVM tests.
                isIncludeAndroidResources = true
                // Deterministic tests: unmocked framework calls return defaults
                // instead of throwing, but our tests use Robolectric/MockK anyway.
                isReturnDefaultValues = true
            }
        }

        packaging {
            resources {
                // Duplicate license files from jaudiotagger & friends.
                excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md}"
            }
        }
    }

    extensions.getByType<KotlinAndroidProjectExtension>().compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            // Opt in to APIs we use deliberately across modules.
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

/** Unit-test dependencies every module gets for free. */
internal fun Project.addCommonTestDependencies() {
    dependencies {
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("mockk").get())
        add("testImplementation", libs.findLibrary("turbine").get())
        add("testImplementation", libs.findLibrary("truth").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    }
}

/** Robolectric + AndroidX test runner deps for Android modules' JVM tests. */
internal fun Project.addAndroidUnitTestDependencies() {
    dependencies {
        add("testImplementation", libs.findLibrary("robolectric").get())
        add("testImplementation", libs.findLibrary("androidx-test-core").get())
        add("testImplementation", libs.findLibrary("androidx-test-junit").get())
    }
}

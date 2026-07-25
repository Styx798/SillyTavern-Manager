import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@DisableCachingByDefault(because = "This task verifies the assembled debug APK byte-for-byte")
abstract class VerifyDebugApkBundledNpmToolAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceArchiveFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceManifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceLicenseInventoryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceNpmLicenseFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val apk = requireRegularNoFollow(apkFile.get().asFile, "debug APK")
        val sourceNpmLicense = requireRegularNoFollow(
            sourceNpmLicenseFile.get().asFile,
            "source npm license asset",
        )
        val sourceNpmLicenseIdentity = sourceNpmLicense.inputStream().use(::hashStream)
        val sources = linkedMapOf(
            APK_ARCHIVE_PATH to requireRegularNoFollow(
                sourceArchiveFile.get().asFile,
                "source npm archive asset",
            ),
            APK_MANIFEST_PATH to requireRegularNoFollow(
                sourceManifestFile.get().asFile,
                "source npm manifest asset",
            ),
            APK_LICENSE_INVENTORY_PATH to requireRegularNoFollow(
                sourceLicenseInventoryFile.get().asFile,
                "source npm license inventory asset",
            ),
            APK_NPM_LICENSE_PATH to sourceNpmLicense,
        )

        ZipFile(apk).use { zip ->
            val allEntries = Collections.list(zip.entries())
            sources.forEach { (apkPath, source) ->
                val matches = allEntries.filter { it.name == apkPath }
                if (matches.size != 1) {
                    throw GradleException(
                        "Debug APK must contain exactly one $apkPath entry; observed ${matches.size}",
                    )
                }
                val entry = matches.single()
                if (entry.isDirectory) throw GradleException("Debug APK asset is a directory: $apkPath")
                val sourceIdentity = source.inputStream().use(::hashStream)
                val apkIdentity = zip.getInputStream(entry).use(::hashStream)
                if (sourceIdentity != apkIdentity || entry.size != sourceIdentity.bytes) {
                    throw GradleException(
                        "Debug APK asset differs from its audited source: $apkPath; " +
                            "source=$sourceIdentity apk=$apkIdentity",
                    )
                }
                if (apkPath == APK_ARCHIVE_PATH) {
                    if (entry.method != ZipEntry.STORED || entry.compressedSize != entry.size) {
                        throw GradleException(
                            "Debug APK npm tool asset must be STORED without outer compression",
                        )
                    }
                    requireNestedNpmLicense(zip, entry, sourceNpmLicenseIdentity)
                }
            }
        }

        logger.lifecycle("Verified debug APK bundled npm assets and STORED npm archive entry")
    }

    private fun requireNestedNpmLicense(
        apk: ZipFile,
        archiveEntry: ZipEntry,
        expectedIdentity: ByteIdentity,
    ) {
        var licenseCount = 0
        ZipInputStream(apk.getInputStream(archiveEntry)).use { nested ->
            while (true) {
                val entry = nested.nextEntry ?: break
                if (entry.name == NPM_LICENSE_PATH && !entry.isDirectory) {
                    licenseCount += 1
                    val nestedIdentity = hashStream(nested)
                    if (nestedIdentity != expectedIdentity) {
                        throw GradleException(
                            "Nested npm LICENSE differs from the audited source: " +
                                "expected=$expectedIdentity nested=$nestedIdentity",
                        )
                    }
                }
                nested.closeEntry()
            }
        }
        if (licenseCount != 1) {
            throw GradleException(
                "Debug APK nested npm archive must contain exactly one $NPM_LICENSE_PATH; " +
                    "observed $licenseCount",
            )
        }
    }

    private fun requireRegularNoFollow(file: File, label: String): File {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw GradleException("$label is missing, linked, or not a regular file: $file")
        }
        return file
    }

    private fun hashStream(input: InputStream): ByteIdentity {
        val digest = MessageDigest.getInstance(SHA_256)
        var bytes = 0L
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            bytes = Math.addExact(bytes, count.toLong())
            digest.update(buffer, 0, count)
        }
        return ByteIdentity(bytes, digest.digest().toHex())
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private data class ByteIdentity(
        val bytes: Long,
        val sha256: String,
    )

    companion object {
        private const val APK_ARCHIVE_PATH =
            "assets/stm_core/tools/npm/11.6.2/npm-11.6.2.stmzip"
        private const val APK_MANIFEST_PATH =
            "assets/stm_core/tools/npm/11.6.2/npm-tool-manifest.stm"
        private const val APK_LICENSE_INVENTORY_PATH =
            "assets/third_party/npm-11.6.2/PACKAGE-LICENSES.json"
        private const val APK_NPM_LICENSE_PATH =
            "assets/third_party/npm-11.6.2/LICENSE.txt"
        private const val NPM_LICENSE_PATH = "npm/LICENSE"
        private const val SHA_256 = "SHA-256"
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.styx798.sillytavernmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.styx798.sillytavernmanager"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner =
            "io.github.styx798.sillytavernmanager.stmcore.StmCoreGate1Instrumentation"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    androidResources {
        // The npm toolchain is already a deterministic ZIP. Keep the final APK entry stored so
        // Core can stream the audited bytes without a second compression layer.
        noCompress += "stmzip"
    }

    lint {
        // STM intentionally ships arm64-v8a only; ChromeOS x86_64 is outside its support scope.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":stm_core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.core)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val stmCoreAssets = project(":stm_core").layout.projectDirectory.dir("src/main/assets")

val verifyDebugApkBundledNpmToolAsset by tasks.registering(
    VerifyDebugApkBundledNpmToolAsset::class,
) {
    group = "verification"
    description = "Verifies fixed npm assets and storage method in the assembled debug APK"
    dependsOn("assembleDebug")
    apkFile.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    sourceArchiveFile.set(
        stmCoreAssets.file("stm_core/tools/npm/11.6.2/npm-11.6.2.stmzip"),
    )
    sourceManifestFile.set(
        stmCoreAssets.file("stm_core/tools/npm/11.6.2/npm-tool-manifest.stm"),
    )
    sourceLicenseInventoryFile.set(
        stmCoreAssets.file("third_party/npm-11.6.2/PACKAGE-LICENSES.json"),
    )
    sourceNpmLicenseFile.set(
        stmCoreAssets.file("third_party/npm-11.6.2/LICENSE.txt"),
    )
}

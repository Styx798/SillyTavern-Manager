package io.github.styx798.sillytavernmanager.stmcore.installer

import android.content.Context
import android.content.res.AssetManager
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import java.io.File

/** Creates the lazy, install-scoped npm toolchain carrier from the signed APK's assets. */
internal object StmBundledNpmToolchainFactory {
    const val MANIFEST_ASSET =
        "stm_core/tools/npm/11.6.2/npm-tool-manifest.stm"
    const val MANIFEST_BYTES = 2_354L
    const val MANIFEST_SHA256 =
        "c7c7396fba9b9d7990c59f65225018f029251395e27d8d422f7e4a7779e1c832"

    fun create(context: Context): StmBundledNpmToolchain {
        val applicationContext = context.applicationContext
        val toolStore = File(StmCorePaths.toolchainsRoot(applicationContext), NPM_STORE_DIRECTORY)
        return StmBundledNpmToolchain(
            storeRoot = toolStore,
            stagingRoot = StmCorePaths.stagingRoot(applicationContext),
            manifestAsset = StmBundledNpmAssetBinding(
                assetName = MANIFEST_ASSET,
                bytes = MANIFEST_BYTES,
                sha256 = MANIFEST_SHA256,
            ),
            assetSource = StmBundledNpmAssetSource { assetName ->
                applicationContext.assets.open(assetName, AssetManager.ACCESS_STREAMING)
            },
        )
    }

    private const val NPM_STORE_DIRECTORY = "npm"
}

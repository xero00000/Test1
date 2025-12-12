package com.gamextra4u.fexdroid.steam

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class SteamEnvironmentInstaller(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun prepareEnvironment(onProgress: suspend (String) -> Unit = {}): SteamEnvironment =
        withContext(dispatcher) {
            val installRoot = File(context.filesDir, "fexdroid").apply { mkdirs() }
            val binDir = File(installRoot, "bin").apply { mkdirs() }
            val runtimeDir = File(installRoot, "runtime").apply { mkdirs() }
            val steamHome = File(installRoot, "steam-home").apply { mkdirs() }

            copyAssetIfNeeded("qemu-x86_64", File(binDir, "qemu-x86_64"), executable = true, onProgress = onProgress)
            copyAssetIfNeeded("libvulkan_freedreno.so", File(binDir, "libvulkan_freedreno.so"), executable = false, onProgress = onProgress)
            copyAssetIfNeeded("fexdroid", File(binDir, "fexdroid"), executable = true, onProgress = onProgress)
            copyAssetIfNeeded("install", File(binDir, "install"), executable = true, onProgress = onProgress, optional = true)
            copyAssetIfNeeded("rootfs", File(binDir, "rootfs"), executable = true, onProgress = onProgress, optional = true)

            val launchScript = File(binDir, LAUNCH_SCRIPT_NAME)
            if (!launchScript.exists()) {
                onProgress("Creating Steam Big Picture launch script")
                launchScript.writeText(buildLaunchScript())
            }
            launchScript.setExecutable(true, false)

            SteamEnvironment(installRoot, binDir, runtimeDir, launchScript, steamHome)
        }

    private suspend fun copyAssetIfNeeded(
        assetName: String,
        destination: File,
        executable: Boolean,
        onProgress: suspend (String) -> Unit,
        optional: Boolean = false
    ) {
        if (destination.exists() && destination.length() > 0L) {
            destination.setExecutable(executable, false)
            return
        }
        try {
            onProgress("Installing $assetName")
            context.assets.open(assetName).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (error: FileNotFoundException) {
            if (optional) {
                return
            }
            throw IOException("Missing bundled asset: $assetName", error)
        }
        destination.setReadable(true, false)
        destination.setExecutable(executable, false)
    }

    private fun buildLaunchScript(): String {
        val flags = loadBigPictureFlags()
        val flagLine = flags.joinToString(" ") { "\"$it\"" }
        return """
            #!/system/bin/sh
            set -e
            SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            INSTALL_ROOT="${'$'}(dirname "${'$'}SCRIPT_DIR")"
            STEAM_HOME="${'$'}INSTALL_ROOT/steam-home"
            LOG_DIR="${'$'}INSTALL_ROOT/logs"
            LAUNCH_LOG="${'$'}LOG_DIR/steam-launch.log"
            mkdir -p "${'$'}LOG_DIR"
            mkdir -p "${'$'}STEAM_HOME"
            export HOME="${'$'}STEAM_HOME"
            export LD_LIBRARY_PATH="${'$'}SCRIPT_DIR:${'$'}LD_LIBRARY_PATH"
            export STEAM_RUNTIME="${'$'}INSTALL_ROOT/runtime"
            export STEAM_CONFIG="${'$'}STEAM_HOME/config"
            echo "[FEXDroid] Launching Steam Big Picture with flags: $flagLine" >> "${'$'}LAUNCH_LOG"
            if [ -x "${'$'}SCRIPT_DIR/qemu-x86_64" ] && [ -f "${'$'}STEAM_HOME/steam.sh" ]; then
                exec "${'$'}SCRIPT_DIR/qemu-x86_64" "${'$'}STEAM_HOME/steam.sh" $flagLine "${'$'}@"
            elif [ -x "${'$'}SCRIPT_DIR/qemu-x86_64" ]; then
                echo "[FEXDroid] Steam runtime not provisioned yet. Big Picture launch deferred." >> "${'$'}LAUNCH_LOG"
                sleep 2
                exit 0
            else
                echo "[FEXDroid] qemu-x86_64 missing - cannot start Steam." >&2
                exit 127
            fi
        """.trimIndent()
    }

    private fun loadBigPictureFlags(): List<String> =
        runCatching {
            context.assets.open("steam/big_picture_flags.json").bufferedReader().use { it.readText() }
        }.mapCatching { json ->
            val array = JSONObject(json).getJSONArray("flags")
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getString(index))
                }
            }
        }.getOrDefault(DEFAULT_FLAGS)

    companion object {
        private const val LAUNCH_SCRIPT_NAME = "steam-big-picture.sh"
        private val DEFAULT_FLAGS = listOf("-tenfoot", "-bigpicture", "-fulldesktopres")
    }
}

data class SteamEnvironment(
    val installRoot: File,
    val binariesDir: File,
    val runtimeDir: File,
    val launchScript: File,
    val steamHome: File
)

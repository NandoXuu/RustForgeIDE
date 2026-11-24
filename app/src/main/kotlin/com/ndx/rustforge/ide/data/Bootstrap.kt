package com.ndx.rustforge.ide.data

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.Paths
import java.util.zip.ZipInputStream

object Bootstrap {
    private const val ASSET = "bootstrap-aarch64.zip"
    private const val MARKER = ".rustforge_ready"
    private const val MODES_FILE = "RUSTFORGE-MODES.txt"
    private const val LINKS_FILE = "SYMLINKS.txt"

    fun prefix(context: Context): File = File(context.filesDir, "usr")
    fun home(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }

    // Termux packages are compiled against /data/data/<applicationId>/files/usr.
    // Android may expose the same private directory as /data/user/0/<applicationId>/files.
    // Use the legacy alias in the process environment so compiled prefix strings match.
    private fun runtimeFilesDir(context: Context): String {
        val path = context.filesDir.absolutePath
        val userZeroPrefix = "/data/user/0/"
        return if (path.startsWith(userZeroPrefix)) {
            "/data/data/" + path.removePrefix(userZeroPrefix)
        } else {
            path
        }
    }

    private fun runtimePrefix(context: Context): String = "${runtimeFilesDir(context)}/usr"
    private fun runtimeHome(context: Context): String = "${runtimeFilesDir(context)}/home"

    fun isReady(context: Context): Boolean {
        val p = prefix(context)
        val stdDir = File(p, "lib/rustlib/aarch64-linux-android/lib")
        val hasAndroidStd = stdDir.listFiles()?.any {
            it.name.startsWith("libstd-") && it.name.endsWith(".rlib") && it.isFile
        } == true
        return File(p, MARKER).isFile &&
            File(p, MARKER).readText().trim() == "rustforge-aarch64-v1" &&
            File(p, "bin/bash").canExecute() &&
            File(p, "bin/rustc").canExecute() &&
            File(p, "bin/cargo").canExecute() &&
            hasAndroidStd
    }

    /**
     * Extracts to a staging directory first. The ready marker is deliberately
     * not written here: SetupViewModel writes it only after runtime validation.
     */
    fun extract(context: Context) {
        val finalPrefix = prefix(context)
        val staging = File(context.filesDir, "usr.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        home(context)

        val links = mutableListOf<Pair<String, String>>()
        val modes = mutableListOf<Pair<Int, String>>()

        try {
            ZipInputStream(context.assets.open(ASSET).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = normalizeEntry(entry.name)
                    if (name == null) {
                        zip.closeEntry()
                        continue
                    }

                    when (name) {
                        LINKS_FILE -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                                val split = line.split("←", limit = 2)
                                require(split.size == 2) { "Linha inválida em $LINKS_FILE: $line" }
                                links += split[0] to split[1]
                            }
                        }
                        MODES_FILE -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                                val split = line.split('\t', limit = 2)
                                require(split.size == 2) { "Linha inválida em $MODES_FILE: $line" }
                                modes += Integer.parseInt(split[0], 8) to (normalizeEntry(split[1])
                                    ?: error("Caminho inválido no mapa de permissões"))
                            }
                        }
                        else -> {
                            val target = File(staging, name)
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().buffered().use { output -> zip.copyTo(output) }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            require(modes.isNotEmpty()) { "O bootstrap não contém $MODES_FILE; permissões não podem ser preservadas." }

            // Recreate links only after regular files have been extracted.
            links.forEach { (rawTarget, rawLink) ->
                val linkName = normalizeEntry(rawLink) ?: error("Link inválido: $rawLink")
                val link = File(staging, linkName)
                val targetPath = Paths.get(rawTarget)
                if (targetPath.isAbsolute) {
                    val allowedSystemRoots = listOf("/system/", "/apex/", "/vendor/", "/product/", "/odm/")
                    require(
                        allowedSystemRoots.any { rawTarget.startsWith(it) } &&
                            rawTarget.split('/').none { it == ".." }
                    ) { "Link simbólico absoluto fora das partições Android permitidas: $rawLink -> $rawTarget" }
                } else {
                    val resolved = link.parentFile.toPath().resolve(targetPath).normalize()
                    require(resolved.startsWith(staging.toPath().normalize())) {
                        "Link simbólico escapa do prefixo: $rawLink -> $rawTarget"
                    }
                }
                link.parentFile?.mkdirs()
                if (link.exists() || link.isSymbolicLink()) link.delete()
                Os.symlink(rawTarget, link.absolutePath)
            }

            // Apply exact modes recorded by the CI packager. Directories are
            // applied deepest-first so their final mode does not block extraction.
            modes.filter { !File(staging, it.second).isDirectory }
                .forEach { (mode, relative) ->
                    val file = File(staging, relative)
                    if (!file.isSymbolicLink() && file.exists()) Os.chmod(file.absolutePath, mode)
                }
            modes.filter { File(staging, it.second).isDirectory }
                .sortedByDescending { it.second.count { c -> c == '/' } }
                .forEach { (mode, relative) ->
                    val dir = File(staging, relative)
                    if (dir.isDirectory && !dir.isSymbolicLink()) Os.chmod(dir.absolutePath, mode)
                }

            require(File(staging, "bin/bash").isFile) { "bash ausente no bootstrap." }
            require(File(staging, "bin/rustc").exists()) { "rustc ausente no bootstrap." }
            require(File(staging, "bin/cargo").exists()) { "Cargo ausente no bootstrap." }
            require(File(staging, "lib/rustlib/aarch64-linux-android/lib").isDirectory) {
                "Biblioteca padrão do target aarch64-linux-android ausente."
            }

            if (finalPrefix.exists()) finalPrefix.deleteRecursively()
            check(staging.renameTo(finalPrefix)) { "Não foi possível ativar o prefixo extraído." }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun validate(context: Context): String {
        val p = runtimePrefix(context)
        val env = env(context)
        val script = """
            set -eu
            test -x "${'$'}PREFIX/bin/bash"
            test -x "${'$'}PREFIX/bin/rustc"
            test -x "${'$'}PREFIX/bin/cargo"
            test -f "${'$'}PREFIX/etc/tls/cert.pem"
            test -f "${'$'}PREFIX/etc/apt/sources.list"
            test -d "${'$'}PREFIX/tmp"
            if grep -R -E '^[[:space:]]*(deb[[:space:]]|deb-src[[:space:]]|Types:)' "${'$'}PREFIX/etc/apt/sources.list" "${'$'}PREFIX/etc/apt/sources.list.d" 2>/dev/null; then
                echo "APT upstream repositories must remain disabled"
                exit 31
            fi
            bash --version | head -n 1
            rustc --version
            cargo --version
            target_libdir="${'$'}(rustc --print target-libdir --target aarch64-linux-android)"
            echo "target-libdir=${'$'}target_libdir"
            test -d "${'$'}target_libdir"
            found=0
            for library in "${'$'}target_libdir"/libstd-*.rlib; do
                if test -f "${'$'}library"; then found=1; break; fi
            done
            test "${'$'}found" -eq 1
            echo "RUSTFORGE_BOOTSTRAP_VALID"
        """.trimIndent()

        val process = ProcessBuilder(
            "$p/bin/bash", "--noprofile", "--norc", "-c", script
        ).directory(home(context)).redirectErrorStream(true).apply {
            environment().clear()
            environment().putAll(env)
        }.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0 || !output.contains("RUSTFORGE_BOOTSTRAP_VALID")) {
            throw IllegalStateException("Validação do ambiente falhou (exit=$code):\n$output")
        }
        return output.lineSequence().firstOrNull { it.startsWith("rustc ") }
            ?: throw IllegalStateException("rustc não retornou sua versão:\n$output")
    }

    fun markReady(context: Context) {
        check(File(prefix(context), "bin/bash").canExecute())
        check(File(prefix(context), "bin/rustc").canExecute())
        check(File(prefix(context), "bin/cargo").canExecute())
        File(prefix(context), MARKER).writeText("rustforge-aarch64-v1\n")
    }

    fun env(context: Context): Map<String, String> {
        val p = runtimePrefix(context)
        val h = runtimeHome(context)
        return mapOf(
            "PREFIX" to p,
            "HOME" to h,
            "PATH" to "$p/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "$p/lib:$p/lib64",
            "TMPDIR" to "$p/tmp",
            "CARGO_HOME" to "$h/.cargo",
            "RUSTUP_HOME" to "$h/.rustup",
            "SHELL" to "$p/bin/bash",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "SSL_CERT_FILE" to "$p/etc/tls/cert.pem",
            "CURL_CA_BUNDLE" to "$p/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "$p/etc/tls/cert.pem",
            "PKG_CONFIG_PATH" to "$p/lib/pkgconfig:$p/share/pkgconfig",
            "DEBIAN_FRONTEND" to "noninteractive",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system"
        )
    }

    fun command(context: Context, script: String): List<String> =
        listOf("${runtimePrefix(context)}/bin/bash", "--noprofile", "--norc", "-c", script)

    private fun normalizeEntry(raw: String): String? {
        val cleaned = raw.replace('\\', '/').removePrefix("./")
        if (cleaned.isBlank() || cleaned == ".") return null
        require(cleaned.split('/').none { it == ".." }) {
            "Travessia de diretório bloqueada no bootstrap: $raw"
        }
        val path = Paths.get(cleaned).normalize()
        require(!path.isAbsolute && path.none { it.toString() == ".." }) {
            "Caminho inseguro no bootstrap: $raw"
        }
        return path.toString().replace(File.separatorChar, '/')
    }

    private fun File.isSymbolicLink(): Boolean =
        try { Os.readlink(absolutePath); true } catch (_: Exception) { false }
}

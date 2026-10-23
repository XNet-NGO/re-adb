package org.xnet.readb.shell

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads and configures the rootless Alpine 3.21 proot rootfs.
 *
 * Ported from shellbox's com.shellbox.shell.ProotBootstrap, retargeted from
 * Ubuntu 24.04 to Alpine 3.21.3. Alpine is musl + busybox so the Ubuntu
 * dpkg/apt/locale-gen patching does NOT apply and has been stripped. What is
 * kept (and adapted for Alpine):
 *   - directory creation
 *   - libtalloc.so -> libtalloc.so.2 copy (proot needs it)
 *   - bsdtar extraction with --strip-components=1 + the hardlink-failure fix
 *   - resolv.conf (8.8.8.8 / 8.8.4.4 / 1.1.1.1)
 *   - executable-bit fixes on bin/lib dirs + ELF interpreters
 *   - a sensible /etc/profile and /root/.profile (PATH + TERM=xterm-256color)
 *   - an Alpine /etc/apk/repositories file (v3.21/main + community)
 */
object ProotBootstrap {

    private const val TAG = "ProotBootstrap"

    // Version marker — bump to force a re-extract when the rootfs changes.
    private const val BOX_ROOTFS_VERSION = "rootfs_alpine_3.21.3"

    private const val ALPINE_VERSION = "v3.21"
    private const val ROOTFS_URL =
        "https://github.com/XNet-NGO/deps/releases/download/rootfs-alpine-3.21.3/box-alpine-3.21-aarch64.tar.xz"
    private const val ROOTFS_SHA256_URL = "$ROOTFS_URL.sha256"

    fun envDir(ctx: Context) = File(ctx.filesDir, "env")
    fun rootfsDir(ctx: Context) = File(envDir(ctx), "alpine")
    private fun marker(ctx: Context, name: String) = File(envDir(ctx), ".$name")

    fun isInstalled(ctx: Context): Boolean {
        val rootfs = rootfsDir(ctx)
        return marker(ctx, BOX_ROOTFS_VERSION).exists() &&
            rootfs.isDirectory &&
            rootfsExtracted(rootfs)
    }

    /**
     * True if the Alpine rootfs looks extracted. We key on bin/busybox (a real
     * regular file), NOT bin/sh: on Alpine bin/sh is a symlink to the ABSOLUTE
     * path /bin/busybox, so File("bin/sh").isFile resolves it against the
     * Android host root (where /bin/busybox does not exist) and returns false
     * even though extraction succeeded. busybox is the authoritative marker.
     */
    private fun rootfsExtracted(rootfs: File): Boolean =
        File(rootfs, "bin/busybox").isFile

    /**
     * Find proot-xed binary, handling Android renaming libproot-xed.so to
     * libroot-xed.so (the "lib" prefix strip some loaders apply). Kept verbatim
     * from shellbox.
     */
    fun findProotXed(ctx: Context): File? {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        for (name in arrayOf("libproot-xed.so", "libroot-xed.so")) {
            val f = File(nativeDir, name)
            if (f.isFile) return f
        }
        return null
    }

    /** Re-apply patches to an existing rootfs (e.g. after an app update). */
    fun ensurePatched(ctx: Context, log: (String) -> Unit) {
        val rootfs = rootfsDir(ctx)
        if (rootfs.isDirectory) {
            patchRootfs(rootfs, log)
        }
    }

    /**
     * Full bootstrap: create dirs, copy talloc, download rootfs, extract, patch.
     * Call on a background thread.
     */
    fun setup(ctx: Context, log: (String) -> Unit): Boolean {
        try {
            val filesDir = ctx.filesDir
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val envDir = envDir(ctx)
            val rootfs = rootfsDir(ctx)

            // 1. Create dirs
            log("Creating directories...")
            listOf(envDir, rootfs, File(filesDir, "tmp"), File(filesDir, "home")).forEach { it.mkdirs() }

            // 2. Copy libtalloc.so -> libtalloc.so.2 (proot needs this)
            val tallocSrc = File(nativeDir, "libtalloc.so")
            val tallocDst = File(filesDir, "libtalloc.so.2")
            if (tallocSrc.exists()) {
                tallocSrc.inputStream().use { i -> tallocDst.outputStream().use { o -> i.copyTo(o) } }
                log("libtalloc.so.2 ready (${tallocDst.length()} bytes)")
            } else {
                log("ERROR: libtalloc.so not found in $nativeDir")
                return false
            }

            // 2b. Prepare bsdtar binary
            val bsdtar = prepareBsdtar(ctx)
            if (bsdtar == null) {
                log("ERROR: libbsdtar.so not found in $nativeDir")
                return false
            }

            // 3. Download rootfs if needed — use version marker to detect upgrades
            val boxMarker = marker(ctx, BOX_ROOTFS_VERSION)
            val rootfsHasSh = rootfsExtracted(rootfs)
            if (!boxMarker.exists() || !rootfsHasSh) {
                // Remove old rootfs if present (upgrade path)
                if (rootfs.isDirectory) {
                    log("Removing old rootfs for Alpine upgrade...")
                    rootfs.deleteRecursively()
                    rootfs.mkdirs()
                }

                val tarball = File(envDir, "rootfs.tar.xz")

                log("Downloading Alpine 3.21 rootfs (aarch64)...")
                download(ROOTFS_URL, tarball, log)

                // Verify checksum if the .sha256 sidecar is reachable (best-effort).
                verifySha256(tarball, log)

                log("Extracting rootfs...")
                val ok = runBsdtar(bsdtar, tarball, rootfs, stripComponents = 1, log = log)
                tarball.delete()
                // Verify by busybox presence, NOT bin/sh (an absolute symlink that
                // Java resolves against the host root). bsdtar may also return
                // nonzero purely from Android SELinux symlink/hardlink denials
                // while still extracting the tree, so busybox is authoritative.
                if (!rootfsExtracted(rootfs)) {
                    log("ERROR: Extraction failed - bin/busybox not found in rootfs (bsdtar ok=$ok)")
                    return false
                }
                if (!ok) log("Note: bsdtar reported errors (likely SELinux symlink denials); busybox present, continuing")
                boxMarker.writeText("box-alpine-3.21")
                log("Rootfs extracted (Alpine release)")
            } else {
                log("Rootfs already installed")
            }

            // 4. Patch rootfs
            patchRootfs(rootfs, log)

            log("Alpine environment ready!")
            return true
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            Log.e(TAG, "setup failed", e)
            return false
        }
    }

    private fun prepareBsdtar(ctx: Context): File? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libbsdtar.so")
        return if (f.exists() && f.canExecute()) f else null
    }

    private fun runBsdtar(
        bsdtar: File,
        tarball: File,
        destDir: File,
        stripComponents: Int,
        log: (String) -> Unit
    ): Boolean {
        destDir.mkdirs()
        val cmd = mutableListOf(
            bsdtar.absolutePath, "-xf", tarball.absolutePath,
            "-C", destDir.absolutePath, "--no-same-owner"
        )
        if (stripComponents > 0) {
            cmd.addAll(listOf("--strip-components", stripComponents.toString()))
        }
        Log.d(TAG, "bsdtar: ${cmd.joinToString(" ")}")
        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = bsdtar.parentFile?.absolutePath ?: ""
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(300, TimeUnit.SECONDS)
            val exit = if (ok) proc.exitValue() else -1
            if (exit != 0) {
                if (!ok) {
                    proc.destroyForcibly(); log("bsdtar timed out"); return false
                }
                val lines = output.lines().filter { it.isNotBlank() }
                val hardlinkErrors = lines.filter { "Can't create" in it || "Permission denied" in it }
                val otherErrors = lines.filter {
                    it.isNotBlank() && "Can't create" !in it && "Permission denied" !in it &&
                        "Error exit delayed" !in it
                }
                if (otherErrors.isEmpty() && hardlinkErrors.isNotEmpty()) {
                    log("bsdtar: ${hardlinkErrors.size} hardlinks failed (Android limitation), fixing...")
                    fixFailedHardlinks(destDir, output, log)
                    true
                } else {
                    log("bsdtar error (exit $exit): ${output.take(500)}")
                    false
                }
            } else {
                if (output.isNotBlank()) Log.d(TAG, "bsdtar output: ${output.take(200)}")
                true
            }
        } catch (e: Exception) {
            log("bsdtar failed: ${e.message}")
            Log.e(TAG, "runBsdtar failed", e)
            false
        }
    }

    /**
     * Android's app-private filesystem cannot create hardlinks across certain
     * paths; when bsdtar reports "Can't create ... Permission denied" for a
     * hardlink, copy the link target to the link path as a regular file. For
     * Alpine most hardlinks are busybox applets, so the generic fallback below
     * copies the already-extracted target file whenever we can resolve it from
     * the error message "<link>: Can't create hard link to '<target>'".
     */
    private fun fixFailedHardlinks(destDir: File, bsdtarOutput: String, log: (String) -> Unit) {
        var fixed = 0
        var attempted = 0

        // libarchive/bsdtar emits lines such as:
        //   bin/foo: Can't create hard link to 'bin/busybox'
        //   bin/foo: Can't create '...': Permission denied
        val hardlinkRegex = Regex("""^(.+?): Can't create hard link to ['"](.+?)['"]""")
        val permRegex = Regex("""^(.+?): Can't create ['"]?(.+?)['"]?: Permission denied""")

        for (line in bsdtarOutput.lines()) {
            val hl = hardlinkRegex.find(line)
            if (hl != null) {
                attempted++
                val linkPath = hl.groupValues[1].trim()
                val targetPath = hl.groupValues[2].trim()
                if (copyAsRegular(destDir, targetPath, linkPath)) fixed++
                continue
            }
            // On Alpine almost everything in /bin, /sbin, /usr/bin is a busybox
            // applet symlink/hardlink, so a sensible fallback target is busybox.
            val perm = permRegex.find(line)
            if (perm != null) {
                attempted++
                val linkPath = perm.groupValues[1].trim()
                if (copyAsRegular(destDir, "bin/busybox", linkPath)) fixed++
            }
        }
        log("  Fixed $fixed/$attempted hardlinks (copied as regular files)")
    }

    private fun copyAsRegular(destDir: File, target: String, link: String): Boolean {
        val targetFile = File(destDir, target)
        val linkFile = File(destDir, link)
        if (targetFile.exists() && !linkFile.exists()) {
            return try {
                linkFile.parentFile?.mkdirs()
                targetFile.inputStream().use { i -> linkFile.outputStream().use { o -> i.copyTo(o) } }
                linkFile.setExecutable(targetFile.canExecute())
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fix hardlink $link -> $target: ${e.message}")
                false
            }
        }
        return false
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit) {
        var url = URL(urlStr)
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000; conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc)
                conn.disconnect()
                if (++redirects > 5) {
                    log("ERROR: too many redirects"); return
                }
                continue
            }
            if (code != 200) {
                log("ERROR: HTTP $code from $url")
                conn.disconnect()
                return
            }
            break
        }
        val total = conn.contentLength.toLong()
        var downloaded = 0L
        var lastReportedMB = -1L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    val mb = downloaded / (1024 * 1024)
                    if (total > 0 && mb > lastReportedMB) {
                        lastReportedMB = mb
                        log("  ${mb}MB / ${total / (1024 * 1024)}MB")
                    }
                }
            }
        }
        conn.disconnect()
        log("  Download complete (${dest.length() / 1024}KB)")
    }

    /**
     * Best-effort SHA-256 verification against the .sha256 sidecar. Non-fatal:
     * if the sidecar can't be fetched we log and proceed (mirrors shellbox's
     * tolerant download path).
     */
    private fun verifySha256(tarball: File, log: (String) -> Unit) {
        try {
            val expected = fetchText(ROOTFS_SHA256_URL)?.trim()
                ?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
            if (expected.isNullOrEmpty()) {
                log("  (sha256 sidecar unavailable, skipping verification)")
                return
            }
            val md = MessageDigest.getInstance("SHA-256")
            tarball.inputStream().use { input ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual == expected) {
                log("  sha256 verified")
            } else {
                log("  WARNING: sha256 mismatch (expected $expected got $actual)")
            }
        } catch (e: Exception) {
            log("  (sha256 verification skipped: ${e.message})")
        }
    }

    private fun fetchText(urlStr: String): String? {
        var url = URL(urlStr)
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: return null
                url = URL(url, loc); conn.disconnect()
                if (++redirects > 5) return null
                continue
            }
            if (code != 200) {
                conn.disconnect(); return null
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
    }

    /**
     * Alpine-specific patching. No dpkg/apt/locale-gen: just DNS, apk repos,
     * executable bits, standard dirs, and shell profiles.
     */
    private fun patchRootfs(rootfs: File, log: (String) -> Unit) {
        // DNS
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf")
            .writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")

        // Alpine apk repositories
        File(rootfs, "etc/apk").mkdirs()
        File(rootfs, "etc/apk/repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_VERSION/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_VERSION/community\n"
        )

        // Fix permissions on bin dirs
        listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin", "usr/local/sbin").forEach { dir ->
            File(rootfs, dir).walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
        }

        // ELF interpreters / core binaries (musl loader + busybox + shells)
        listOf(
            "lib/ld-musl-aarch64.so.1",
            "lib/libc.musl-aarch64.so.1",
            "usr/bin/env", "bin/sh", "bin/busybox", "bin/ash"
        ).forEach { rel ->
            val f = File(rootfs, rel)
            if (f.exists() && !f.isDirectory) f.setExecutable(true)
        }

        // .so files
        listOf("lib", "usr/lib").forEach { dir ->
            File(rootfs, dir).walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".so") || it.name.contains(".so.")) }
                .forEach { it.setExecutable(true) }
        }

        // Standard dirs
        for (d in listOf("tmp", "var/tmp", "var/cache/apk", "home", "root", "root/workspace")) {
            File(rootfs, d).mkdirs()
        }
        File(rootfs, "tmp").setWritable(true, false)

        // System-wide profile
        File(rootfs, "etc/profile").writeText(
            """
            export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            export HOME=/root
            export TERM=xterm-256color
            export TMPDIR=/tmp
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export COLORTERM=truecolor
            export PS1='\u@readb:\w\$ '
            """.trimIndent() + "\n"
        )

        // Per-user profile for root
        File(rootfs, "root/.profile").writeText(
            """
            export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            export HOME=/root
            export TERM=xterm-256color
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            """.trimIndent() + "\n"
        )

        log("Rootfs patched (DNS, apk repos, exec bits, profiles)")
    }
}

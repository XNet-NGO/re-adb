package org.xnet.readb.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Executes one-shot commands inside the rootless Alpine proot environment.
 * Tracks the running process so it can be cancelled externally.
 *
 * Ported from shellbox's com.shellbox.shell.ProotExecutor; retargeted to the
 * Alpine rootfs (env/alpine), musl + busybox. The proot argument construction
 * mirrors ProotExecutor.buildProotArgs()/buildProotEnv() from shellbox, with
 * the rootfs path and the login shell adapted for Alpine (/bin/sh).
 */
object ProotExecutor {

    private const val TAG = "ProotExecutor"

    @Volatile
    private var currentProcess: Process? = null

    fun exec(context: Context, command: String, timeoutMs: Long = 30_000): String {
        val filesDir = context.filesDir.absolutePath
        val rootfs = ProotBootstrap.rootfsDir(context)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val prootBin = ProotBootstrap.findProotXed(context)
            ?: return "Error: proot binary not found in $nativeLibDir"
        if (!rootfs.isDirectory) return "Error: Alpine rootfs not installed. Run setup first."

        // proot needs libtalloc.so.2 next to it on LD_LIBRARY_PATH (filesDir).
        val talloc = File(filesDir, "libtalloc.so.2")
        if (!talloc.exists()) {
            val src = File(nativeLibDir, "libtalloc.so")
            if (src.exists()) src.inputStream().use { i -> talloc.outputStream().use { o -> i.copyTo(o) } }
        }

        File(filesDir, "tmp").mkdirs()

        val args = buildProotArgs(rootfs, filesDir, command)
        val env = buildProotEnv(filesDir, nativeLibDir)

        Log.d(TAG, "exec: ${prootBin.name} ${args.take(5).joinToString(" ")}...")

        return try {
            val pb = ProcessBuilder(listOf(prootBin.absolutePath) + args)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val process = pb.start()
            currentProcess = process

            val output = readAll(process.inputStream, timeoutMs)
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            currentProcess = null
            val out = output.lines()
                .filter { !it.startsWith("proot warning:") && !it.startsWith("proot info:") }
                .joinToString("\n").trim()
            if (out.length > 8000) out.take(8000) + "\n[truncated]"
            else out.ifEmpty { "(no output, exit ${process.exitValue()})" }
        } catch (e: Exception) {
            currentProcess = null
            Log.e(TAG, "exec failed", e)
            "Error: ${e.message}"
        }
    }

    /** Kill any running proot process. */
    fun cancel() {
        currentProcess?.let {
            Log.i(TAG, "Cancelling running proot process")
            it.destroyForcibly()
            currentProcess = null
        }
    }

    private fun readAll(stream: InputStream, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        val buf = ByteArray(8192)
        stream.use { s ->
            while (System.currentTimeMillis() < deadline) {
                val avail = s.available()
                if (avail > 0) {
                    val n = s.read(buf)
                    if (n == -1) break
                    sb.append(String(buf, 0, n))
                } else {
                    val n = s.read(buf)
                    if (n == -1) break
                    sb.append(String(buf, 0, n))
                }
            }
        }
        return sb.toString()
    }

    private fun buildProotArgs(rootfs: File, filesDir: String, command: String): List<String> {
        val args = mutableListOf("--kill-on-exit")

        fun bind(src: String, dst: String? = null) {
            args.addAll(listOf("-b", if (dst != null) "$src:$dst" else src))
        }

        for (mnt in listOf(
            "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
            "/linkerconfig/ld.config.txt",
            "/linkerconfig/com.android.art/ld.config.txt"
        )) {
            val f = File(mnt)
            if (f.exists()) bind(f.canonicalPath)
        }

        bind("/dev")
        bind("/dev/urandom", "/dev/random")
        bind("/proc")
        bind("/sys")
        bind("/proc/self/fd", "/dev/fd")
        bind(filesDir)

        val tmpDir = File(rootfs, "tmp").also { it.mkdirs() }
        bind(tmpDir.absolutePath, "/dev/shm")

        bind("/proc/self/fd/0", "/dev/stdin")
        bind("/proc/self/fd/1", "/dev/stdout")
        bind("/proc/self/fd/2", "/dev/stderr")

        val fipsFile = File(tmpDir, "fips_enabled").also { it.writeText("0\n") }
        bind(fipsFile.absolutePath, "/proc/sys/crypto/fips_enabled")

        // Alpine is musl + busybox: login shell is /bin/sh, env lives at /usr/bin/env
        // (busybox provides it). Run the one-shot command through /bin/sh -c.
        args.addAll(
            listOf(
                "-r", rootfs.absolutePath,
                "-0", "--link2symlink", "--sysvipc", "-L",
                "-w", "/root",
                "/usr/bin/env",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME=/root", "USER=root", "TERM=xterm-256color",
                "TMPDIR=/tmp", "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
                "/bin/sh", "-c", command
            )
        )
        return args
    }

    private fun buildProotEnv(filesDir: String, nativeLibDir: String): Map<String, String> {
        val env = mutableMapOf(
            "PROOT_TMP_DIR" to "$filesDir/tmp",
            // libtalloc.so.2 lives in filesDir; proot loaders in nativeLibDir.
            "LD_LIBRARY_PATH" to "$filesDir:$nativeLibDir"
        )
        val loader = File(nativeLibDir, "libproot.so")
        val loader32 = File(nativeLibDir, "libproot32.so")
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        if (loader32.exists()) env["PROOT_LOADER32"] = loader32.absolutePath
        return env
    }
}

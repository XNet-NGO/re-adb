package org.xnet.readb.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.xnet.readb.shell.ProotBootstrap
import java.io.File

/**
 * Factory for launching an interactive Alpine login shell inside a PTY.
 *
 * The termux [TerminalSession] forks a PTY around an arbitrary executable +
 * args + env, so we point it at the proot binary (libproot-xed.so) with the
 * exact proot argument array shellbox's MainActivity uses, adapted for the
 * Alpine rootfs (env/alpine) and its musl + busybox login shell (/bin/sh -l).
 *
 * The proot command we launch is:
 *   libproot-xed.so --kill-on-exit -r <rootfs> -0 -w /root \
 *     -b /dev -b /proc -b /sys [-b /sdcard] [-b <android mounts>] \
 *     --link2symlink --sysvipc -L /bin/sh -l
 *
 * LD_LIBRARY_PATH includes filesDir (which holds libtalloc.so.2, copied by
 * ProotBootstrap.setup) and nativeLibraryDir (which holds the proot loaders).
 */
object ReadbTerminal {

    /** Transcript scrollback used by the emulator. */
    private const val TRANSCRIPT_ROWS = 2000

    /**
     * Create (but not yet size) a TerminalSession running an interactive Alpine
     * login shell under proot. The returned session's emulator is initialized
     * lazily by TerminalView.updateSize() once the view has a width/height.
     *
     * @throws IllegalStateException if the proot binary or rootfs is missing.
     */
    fun newAlpineSession(ctx: Context, client: TerminalSessionClient): TerminalSession {
        val filesDir = ctx.filesDir.absolutePath
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        val rootfs = ProotBootstrap.rootfsDir(ctx)

        val prootBin = ProotBootstrap.findProotXed(ctx)
            ?: error("proot binary not found in $nativeLibDir")
        if (!rootfs.isDirectory) error("Alpine rootfs not installed. Run ProotBootstrap.setup first.")

        // Ensure libtalloc.so.2 is present next to the loaders on LD_LIBRARY_PATH.
        val talloc = File(filesDir, "libtalloc.so.2")
        if (!talloc.exists()) {
            val src = File(nativeLibDir, "libtalloc.so")
            if (src.exists()) src.inputStream().use { i -> talloc.outputStream().use { o -> i.copyTo(o) } }
        }
        File(filesDir, "tmp").mkdirs()

        val args = buildProotArgs(rootfs, filesDir)
        val env = buildProotEnv(filesDir, nativeLibDir)

        // termux TerminalSession(shellPath, cwd, args, env, transcriptRows, client)
        // NOTE: by convention argv[0] == the executable path, so args[0] is the
        // proot binary path and the remaining entries are the proot arguments.
        return TerminalSession(
            prootBin.absolutePath,
            filesDir,
            args,
            env,
            TRANSCRIPT_ROWS,
            client
        )
    }

    /**
     * Build the argv for the proot invocation. argv[0] is the proot binary
     * itself (required by the exec convention the PTY uses); the rest are the
     * proot arguments ending in the interactive login shell `/bin/sh -l`.
     */
    private fun buildProotArgs(rootfs: File, filesDir: String): Array<String> {
        val args = mutableListOf<String>()
        // argv[0]
        args.add("libproot-xed.so")
        args.add("--kill-on-exit")

        fun bind(src: String, dst: String? = null) {
            args.add("-b")
            args.add(if (dst != null) "$src:$dst" else src)
        }

        // Android system mounts that the dynamic linker inside the guest needs.
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

        // Expose shared external storage if available (useful inside the shell).
        File("/sdcard").let { if (it.exists()) bind(it.absolutePath) }

        val tmpDir = File(rootfs, "tmp").also { it.mkdirs() }
        bind(tmpDir.absolutePath, "/dev/shm")

        bind("/proc/self/fd/0", "/dev/stdin")
        bind("/proc/self/fd/1", "/dev/stdout")
        bind("/proc/self/fd/2", "/dev/stderr")

        val fipsFile = File(tmpDir, "fips_enabled").also { it.writeText("0\n") }
        bind(fipsFile.absolutePath, "/proc/sys/crypto/fips_enabled")

        // Rootfs + rootless emulation options, then the interactive login shell.
        args.addAll(
            listOf(
                "-r", rootfs.absolutePath,
                "-0",
                "-w", "/root",
                "--link2symlink", "--sysvipc", "-L",
                "/bin/sh", "-l"
            )
        )
        return args.toTypedArray()
    }

    /**
     * Environment passed to the proot process. The guest's own PATH/HOME/TERM
     * are set by /etc/profile and /root/.profile (login shell), so here we only
     * set what the proot *loader* on the Android side needs.
     */
    private fun buildProotEnv(filesDir: String, nativeLibDir: String): Array<String> {
        val env = mutableListOf(
            "PROOT_TMP_DIR=$filesDir/tmp",
            // libtalloc.so.2 is in filesDir; proot loaders are in nativeLibDir.
            "LD_LIBRARY_PATH=$filesDir:$nativeLibDir",
            "HOME=$filesDir",
            "TERM=xterm-256color"
        )
        val loader = File(nativeLibDir, "libproot.so")
        val loader32 = File(nativeLibDir, "libproot32.so")
        if (loader.exists()) env.add("PROOT_LOADER=${loader.absolutePath}")
        if (loader32.exists()) env.add("PROOT_LOADER32=${loader32.absolutePath}")
        return env.toTypedArray()
    }
}

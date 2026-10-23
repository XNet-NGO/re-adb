# PORT_NOTES_terminal.md

Port of the PTY terminal view + rootless Alpine PRoot bootstrap from
`/home/ubuntu/projects/shellbox` (`com.shellbox`) into re-adb
(`org.xnet.readb`, Views-based, minSdk 26, Java 17, AndroidX).

## Files produced

Terminal view (package `org.xnet.readb.terminal.view` and helpers):
- `terminal/view/TerminalView.java`        <- shellbox terminal/view/TerminalView.java
- `terminal/view/TerminalRenderer.java`    <- shellbox terminal/view/TerminalRenderer.java
- `terminal/view/TerminalViewClient.java`  <- shellbox terminal/view/TerminalViewClient.java
- `terminal/view/gesture/GestureAndScaleRecognizer.kt` <- shellbox (package change only)
- `terminal/component/completion/OnAutoCompleteListener.kt` <- shellbox (package change only)
- `terminal/ReadbTerminal.kt`              <- NEW factory (models shellbox MainActivity session creation)

Shell / PRoot (package `org.xnet.readb.shell`):
- `shell/ProotBootstrap.kt`  <- shellbox shell/ProotBootstrap.kt, retargeted Ubuntu -> Alpine 3.21
- `shell/ProotExecutor.kt`   <- shellbox shell/ProotExecutor.kt, retargeted to Alpine rootfs

Resources:
- `res/drawable/text_select_handle_left_material.xml`  (copied verbatim)
- `res/drawable/text_select_handle_right_material.xml` (copied verbatim)
- `res/drawable-xxhdpi/text_select_handle_left_mtrl_alpha.png`  (copied verbatim)
- `res/drawable-xxhdpi/text_select_handle_right_mtrl_alpha.png` (copied verbatim)
- `res/values/terminal_strings.xml`  (copy_text / paste_text / text_selection_more only,
  in a dedicated file so it merges with the integrator's strings.xml without collision)
- `res/layout/terminal_view.xml`  <- shellbox activity_main.xml, adapted (custom-view FQCN
  changed to org.xnet.readb.terminal.view.TerminalView). Named terminal_view.xml so it does
  not clobber an integrator activity_main.xml.

MainActivity, AndroidManifest and the unified UI are intentionally NOT written (owned by the
integrator). `app/src/main/cpp` and `build.gradle.kts`/portmux fetch logic were not touched.

## Backend: shellbox local fork -> termux gradle dep

shellbox ships its OWN terminal backend under `com.shellbox.terminal.backend.*`
(TerminalSession / TerminalEmulator / JNI / TerminalBuffer / ...), a fork of the
termux emulator. re-adb instead uses the prebuilt termux dep
`com.github.termux.termux-app:terminal-emulator:v0.118.0`, so the view + renderer
were RETARGETED to `com.termux.terminal.*`. API deltas reconciled:

| shellbox local backend                         | termux v0.118.0                                   |
|------------------------------------------------|---------------------------------------------------|
| `EmulatorDebug.LOG_TAG`                        | not present -> local `LOG_TAG` constant           |
| `TerminalEmulator.isShowingCursor()`           | `shouldCursorBeVisible()`                          |
| `TerminalEmulator.CURSOR_STYLE_UNDERLINE/BAR`  | `TERMINAL_CURSOR_STYLE_UNDERLINE / _BAR`           |
| `TerminalSession.clipboardText(String)`        | `TerminalSession.onCopyTextToClipboard(String)`   |
| `SessionChangedCallback` (inner iface)         | `TerminalSessionClient` (separate interface)      |
| `TerminalSession(path,cwd,args,env,cb)`        | `TerminalSession(path,cwd,args,env,Integer,client)`|

All other emulator APIs the view/renderer use (`getScreen`, `getSelectedText`,
`getActiveRows`/`getActiveTranscriptRows`, `getScrollCounter`, `isReverseVideo`,
`isMouseTrackingActive`, `isAlternateBufferActive`, `sendMouseEvent`, `paste`, the
`MOUSE_*` + `UNICODE_REPLACEMENT_CHAR` constants, `KeyHandler.getCode/KEYMOD_*`,
`TextStyle.*`, `WcWidth.width`, `TerminalBuffer.allocateFullLineIfNecessary`,
`TerminalRow.mText/getSpaceUsed/getStyle`) exist unchanged in termux v0.118.0.

The `TerminalSessionClient` callback interface is NOT re-implemented here; the
integrator's MainActivity provides it (it also carries log* and cursor-state hooks
that shellbox's old callback lacked). `ReadbTerminal.newAlpineSession(ctx, client)`
takes that `TerminalSessionClient` and hands it to the termux `TerminalSession`.

## Alpine retarget of the bootstrap (ProotBootstrap.kt)

KEPT (adapted): dir creation; `libtalloc.so -> libtalloc.so.2` copy; bsdtar extraction
with `--strip-components 1` + the hardlink-failure fallback (copy target as a regular
file — generalized for busybox applets since Alpine has no Ubuntu perl/dpkg link table);
`/etc/resolv.conf` (8.8.8.8 / 8.8.4.4 / 1.1.1.1); executable-bit fixes on bin/sbin/usr
dirs, `.so` files and the musl ELF loader; `findProotXed` (libproot-xed.so /
libroot-xed.so rename handling).

ADDED for Alpine: `/etc/apk/repositories` with
`https://dl-cdn.alpinelinux.org/alpine/v3.21/main` and `.../community`; sensible
`/etc/profile` and `/root/.profile` setting PATH + HOME + TERM=xterm-256color
(+ LANG/LC_ALL/COLORTERM); best-effort SHA-256 verification against the `.tar.xz.sha256`
sidecar.

STRIPPED (Ubuntu-only, N/A on musl+busybox): dpkg/apt config files, `dpkg --configure`,
`dpkg-reconfigure locales`, locale.gen patching, `/etc/default/locale`, apt/dpkg exec-bit
fixups, dpkg proot stubs (ldconfig/invoke-rc.d/start-stop-daemon), the big Debian
hardlink name table, Android GID injection into `/etc/group`, and the `.bashrc`/tool
rc files (`.inputrc`/`.wgetrc`/`.curlrc`/`.gitconfig`/pip/npm/nano) — none are needed to
bring up an interactive Alpine shell.

- rootfs URL:   https://github.com/XNet-NGO/deps/releases/download/rootfs-alpine-3.21.3/box-alpine-3.21-aarch64.tar.xz
- sha256:       <above>.sha256
- version marker: `rootfs_alpine_3.21.3`  (marker file `env/.rootfs_alpine_3.21.3`)
- rootfsDir:    `filesDir/env/alpine`

## Exact proot command

Interactive login session built by `ReadbTerminal.newAlpineSession` (argv[0] = the proot
binary itself, which the termux PTY exec convention requires):

    libproot-xed.so --kill-on-exit \
      [-b <android mount canonical path> ...]   # /apex /odm /product /system /system_ext
                                                # /vendor /linkerconfig/ld.config.txt
                                                # /linkerconfig/com.android.art/ld.config.txt
      -b /dev \
      -b /dev/urandom:/dev/random \
      -b /proc \
      -b /sys \
      -b /proc/self/fd:/dev/fd \
      -b <filesDir> \
      -b /sdcard                                # only if present
      -b <rootfs>/tmp:/dev/shm \
      -b /proc/self/fd/0:/dev/stdin \
      -b /proc/self/fd/1:/dev/stdout \
      -b /proc/self/fd/2:/dev/stderr \
      -b <rootfs>/tmp/fips_enabled:/proc/sys/crypto/fips_enabled \
      -r <filesDir>/env/alpine \
      -0 \
      -w /root \
      --link2symlink --sysvipc -L \
      /bin/sh -l

Environment handed to the proot process:

    PROOT_TMP_DIR=<filesDir>/tmp
    LD_LIBRARY_PATH=<filesDir>:<nativeLibraryDir>   # filesDir holds libtalloc.so.2
    HOME=<filesDir>
    TERM=xterm-256color
    PROOT_LOADER=<nativeLibraryDir>/libproot.so       # if present
    PROOT_LOADER32=<nativeLibraryDir>/libproot32.so   # if present

`ProotExecutor.exec()` (one-shot, non-PTY) uses the same bind set but runs
`/usr/bin/env PATH=... HOME=/root USER=root TERM=xterm-256color TMPDIR=/tmp
LANG=C.UTF-8 LC_ALL=C.UTF-8 /bin/sh -c "<command>"` instead of the interactive
`-L /bin/sh -l`, matching shellbox's ProotExecutor.buildProotArgs (Ubuntu `/bin/bash`
swapped for Alpine `/bin/sh`).

## shellbox lines I could not carry over 1:1

- `ShellExecutor.readAll(...)` — shellbox's ProotExecutor delegated output draining to
  `ShellExecutor`, which was out of scope. I inlined an equivalent deadline-bounded
  `readAll(InputStream, timeoutMs)` into `ProotExecutor.kt`.
- `TerminalSession.clipboardText(String)` has no public equivalent in termux v0.118.0;
  the copy action now calls `onCopyTextToClipboard(String)` (semantically identical —
  it routes to the client's clipboard callback).
- `EmulatorDebug` (shellbox-local) doesn't exist in the termux dep; replaced the single
  `EmulatorDebug.LOG_TAG` reference with a private `LOG_TAG` constant in TerminalView.

## Verification performed

- Java (TerminalView/TerminalRenderer/TerminalViewClient) compiled with `javac` against
  `android.jar` (android-35) + the real `terminal-emulator-v0.118.0-api.jar` -> EXIT 0
  (only a deprecation note).
- Kotlin (ProotBootstrap/ProotExecutor/ReadbTerminal/GestureAndScaleRecognizer/
  OnAutoCompleteListener) compiled with the Kotlin 2.x embeddable compiler against the
  same android.jar + termux jar + kotlin-stdlib -> EXIT 0, no diagnostics.
- A full Gradle build was NOT run here: it requires the CMake native engine and the
  integrator's MainActivity + AndroidManifest, which are owned by other agents. The R
  references (`R.drawable.text_select_handle_*`, `R.string.copy_text/paste_text/
  text_selection_more`) are backed by the resources added above and will resolve once
  the app module is assembled.

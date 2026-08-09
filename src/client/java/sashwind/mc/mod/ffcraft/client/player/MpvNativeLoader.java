package sashwind.mc.mod.ffcraft.client.player;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * libmpv 原生库加载器 — 运行时按需下载 + 持久缓存。
 *
 * 缓存目录: {gameDir}/ffcraft/natives/
 * 下载源:   GitHub Releases → 多镜像 fallback
 */
public class MpvNativeLoader {

    private static final Path NATIVE_DIR = FabricLoader.getInstance().getGameDir()
            .resolve("ffcraft").resolve("natives");

    // ---- 状态 ----
    public enum State { LOADED, DOWNLOADING, NOT_INSTALLED, FAILED, UNSUPPORTED }
    private static volatile State state = State.NOT_INSTALLED;
    private static volatile String statusMsg = "";
    private static volatile int downloadProgress; // 0-100
    private static MpvNative mpv;

    public static State getState() { return state; }
    public static String getStatusMsg() { return statusMsg; }
    public static int getDownloadProgress() { return downloadProgress; }
    public static boolean isLoaded() { return state == State.LOADED; }
    public static MpvNative getApi() { return mpv; }

    // ---- 平台信息 ----
    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final String ARCH = System.getProperty("os.arch").toLowerCase();

    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isMac() { return OS.contains("mac"); }
    public static boolean isLinux() { return OS.contains("linux") || OS.contains("nix"); }
    public static boolean isX64() { return ARCH.contains("x86_64") || ARCH.contains("amd64"); }
    public static boolean isArm64() { return ARCH.contains("aarch64") || ARCH.contains("arm64"); }

    public static String getLibName() {
        if (isWindows()) return "libmpv-2";  // zhongfly 构建产出的 DLL 名（JNA 自动加 .dll）
        if (isMac()) return "mpv";           // media-kit 产出 libmpv.dylib，不加版本号
        return "mpv";                        // Linux: libmpv.so
    }

    public static String getLibFileName() {
        String base = getLibName();
        if (isWindows()) return base + ".dll";
        if (isMac()) return "lib" + base + ".dylib";
        return "lib" + base + ".so";
    }

    public static Path getLibPath() { return NATIVE_DIR.resolve(getLibFileName()); }

    /** 手动下载的页面地址（各平台统一跳转到 mpv-winbuild） */
    public static String getDownloadPageUrl() {
        if (isWindows()) return "https://github.com/zhongfly/mpv-winbuild/releases/latest";
        if (isMac()) return "https://github.com/media-kit/libmpv-darwin-build/releases/latest";
        return "https://github.com/zhongfly/mpv-winbuild/releases/latest";
    }

    /** 获取当前平台的镜像索引 */
    private static int getMirrorIndex() {
        if (isWindows()) return 0;
        if (isMac()) return isArm64() ? 2 : 1;
        return 3; // Android (fallback)
    }

    // ---- 镜像列表 ----
    // { apiUrl, archiveKeyword, archiveExt, internalPath }
    // internalPath: 压缩包内目标库文件的路径（"" 表示在根目录）
    private static final String[][] MIRRORS = {
        // 0: Windows x86_64 — zhongfly/mpv-winbuild
        { "https://api.github.com/repos/zhongfly/mpv-winbuild/releases/latest",
          "mpv-dev-lgpl-x86_64", ".7z", "libmpv-2.dll" },
        // 1: macOS x86_64 — media-kit/libmpv-darwin-build
        { "https://api.github.com/repos/media-kit/libmpv-darwin-build/releases/latest",
          "macos-amd64-video-full", ".tar.gz", "libmpv.dylib" },
        // 2: macOS arm64
        { "https://api.github.com/repos/media-kit/libmpv-darwin-build/releases/latest",
          "macos-arm64-video-full", ".tar.gz", "libmpv.dylib" },
        // 3: Android arm64 — jarnedemeulemeester/libmpv-android
        { "https://api.github.com/repos/jarnedemeulemeester/libmpv-android/releases/latest",
          "libmpv-release", ".aar", "jni/arm64-v8a/libmpv.so" },
    };

    // ---- 下载镜像站 ----
    // { 显示名称, 下载域名前缀 (null=原生github) }
    // 按实测速度排序：git.yylx.win 最快，优先使用；github 原生兜底
    private static final String[][] DOWNLOAD_MIRRORS = {
        { "git.yylx.win",          "https://git.yylx.win" },
        { "github.com (原生)",     null },
        { "jiashu.1win.eu.org",    "https://jiashu.1win.eu.org" },
        { "777.z321.cc.cd",        "https://777.z321.cc.cd" },
        { "gg.z321.cc.cd",         "https://gg.z321.cc.cd" },
        { "gh.my-website.ccwu.cc", "https://gh.my-website.ccwu.cc" },
        { "gh.927223.xyz",         "https://gh.927223.xyz" },
    };

    private static volatile int selectedMirror = 0;

    public static int getMirrorCount() { return DOWNLOAD_MIRRORS.length; }
    public static String getMirrorName(int i) { return DOWNLOAD_MIRRORS[i][0]; }
    public static int getSelectedMirror() { return selectedMirror; }
    public static void setSelectedMirror(int i) { selectedMirror = Math.max(0, Math.min(i, DOWNLOAD_MIRRORS.length - 1)); }
    public static void cycleMirror() { selectedMirror = (selectedMirror + 1) % DOWNLOAD_MIRRORS.length; }

    /** 将原始 GitHub URL 通过镜像代理：https://镜像/原始URL */
    private static String applyMirror(String url) {
        String prefix = DOWNLOAD_MIRRORS[selectedMirror][1];
        if (prefix == null) return url; // 原生
        return prefix + "/" + url;
    }

    /** API 地址也通过镜像代理 */
    private static String applyApiMirror(String apiUrl) {
        return applyMirror(apiUrl);
    }

    // ============ 初始化 ============

    /** 在游戏启动时调用。已加载则立即返回，否则返回 false（需等下载完成） */
    public static boolean init() {
        if (mpv != null) { state = State.LOADED; return true; }

        // 1. 检查系统 PATH
        try {
            mpv = Native.load(getLibName(), MpvNative.class);
            state = State.LOADED;
            statusMsg = "已加载系统 libmpv (v" + mpv.mpv_client_api_version() + ")";
            System.out.println("[MpvNative] " + statusMsg);
            return true;
        } catch (UnsatisfiedLinkError ignored) {}

        // 2. 检查缓存目录
        Path libPath = getLibPath();
        if (Files.exists(libPath)) {
            try {
                System.setProperty("jna.library.path",
                        NATIVE_DIR.toString() + File.pathSeparator
                                + System.getProperty("jna.library.path", ""));
                mpv = Native.load(getLibName(), MpvNative.class);
                state = State.LOADED;
                statusMsg = "已加载缓存 libmpv";
                System.out.println("[MpvNative] " + statusMsg);
                return true;
            } catch (UnsatisfiedLinkError e) {
                // DLL 损坏，尝试重新下载
                System.err.println("[MpvNative] 缓存文件无效，将重新下载");
                try { Files.deleteIfExists(libPath); } catch (IOException ignored) {}
            }
        }

        // 3. 检查游戏目录是否有手动放置的压缩包
        if (tryLocalArchive()) return true;

        // 4. 需要下载 — 标记状态
        state = State.NOT_INSTALLED;
        if ((isWindows() || isMac()) && (isX64() || isArm64())) {
            statusMsg = "需要下载 libmpv 原生库（首次使用）";
        } else {
            state = State.UNSUPPORTED;
            statusMsg = "当前平台不支持自动下载，请手动下载压缩包放到游戏目录";
        }
        System.out.println("[MpvNative] " + statusMsg);
        return false;
    }

    // ============ API 探测 ============

    private static volatile Boolean audioRenderApi;

    /**
     * 探测当前 libmpv 是否支持音频渲染 API（mpv_audio_render_context_*，0.33+ 才有）。
     * 旧版本不支持时回退到 ao=auto（mpv 直接输出到系统声卡，无空间音频）。
     */
    public static boolean hasAudioRenderApi() {
        Boolean v = audioRenderApi;
        if (v == null) {
            synchronized (MpvNativeLoader.class) {
                if ((v = audioRenderApi) == null) {
                    v = probeAudioRender();
                    audioRenderApi = v;
                }
            }
        }
        return v;
    }

    private static boolean probeAudioRender() {
        try {
            NativeLibrary lib = NativeLibrary.getInstance(getLibName());
            try { lib.getFunction("mpv_audio_render_context_create"); }
            catch (UnsatisfiedLinkError e) { return false; }
            try { lib.getFunction("mpv_audio_render_context_render"); }
            catch (UnsatisfiedLinkError e) { return false; }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============ 下载 ============

    /** 异步下载 + 安装，自动切换镜像重试 */
    public static CompletableFuture<Boolean> downloadAsync(Consumer<Integer> progressCallback) {
        if (state == State.LOADED) return CompletableFuture.completedFuture(true);
        if (state == State.DOWNLOADING) return CompletableFuture.completedFuture(false);
        // NOT_INSTALLED / FAILED 均可重新下载
        state = State.DOWNLOADING;
        downloadProgress = 0;
        statusMsg = "正在下载 libmpv...";

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 获取下载 URL（已尝试多个镜像 API）
                String originalUrl = resolveDownloadUrl();
                if (originalUrl == null) {
                    state = State.FAILED;
                    statusMsg = "无法获取下载地址，请换镜像或手动下载";
                    return false;
                }

                // 2. 下载（从当前镜像开始，失败则切换）
                Files.createDirectories(NATIVE_DIR);
                String ext = MIRRORS[getMirrorIndex()][2];
                Path tmpFile = NATIVE_DIR.resolve("mpv_download" + ext);

                boolean downloaded = false;
                int startMirror = selectedMirror;
                for (int attempt = 0; attempt < DOWNLOAD_MIRRORS.length; attempt++) {
                    int mi = (startMirror + attempt) % DOWNLOAD_MIRRORS.length;
                    String prefix = DOWNLOAD_MIRRORS[mi][1];
                    String dlUrl = (prefix == null) ? originalUrl : prefix + "/" + originalUrl;
                    try {
                        statusMsg = "正在下载 (" + DOWNLOAD_MIRRORS[mi][0] + ")...";
                        downloadFile(dlUrl, tmpFile, progressCallback);
                        downloaded = true;
                        System.out.println("[MpvNative] 下载成功: " + DOWNLOAD_MIRRORS[mi][0]);
                        break;
                    } catch (IOException e) {
                        System.out.println("[MpvNative] 镜像 " + DOWNLOAD_MIRRORS[mi][0] + " 下载失败: " + e.getMessage());
                        // 删除不完整的文件
                        try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
                    }
                }
                if (!downloaded) {
                    state = State.FAILED;
                    statusMsg = "所有镜像下载失败，请手动下载";
                    return false;
                }

                // 3. 解压
                statusMsg = "正在安装...";
                extractDll(tmpFile, NATIVE_DIR);
                Files.deleteIfExists(tmpFile);

                // 4. 加载
                System.setProperty("jna.library.path",
                        NATIVE_DIR.toString() + File.pathSeparator
                                + System.getProperty("jna.library.path", ""));
                mpv = Native.load(getLibName(), MpvNative.class);
                state = State.LOADED;
                statusMsg = "libmpv 安装完成 (v" + mpv.mpv_client_api_version() + ")";
                System.out.println("[MpvNative] " + statusMsg);
                return true;

            } catch (Exception e) {
                state = State.FAILED;
                statusMsg = "安装失败: " + e.getMessage();
                System.err.println("[MpvNative] " + statusMsg);
                e.printStackTrace();
                return false;
            }
        });
    }

    // ---- URL 解析 ----

    /** 解析下载 URL，依次尝试各个镜像站 */
    private static String resolveDownloadUrl() throws IOException {
        int mi = getMirrorIndex();
        String apiUrl = MIRRORS[mi][0];
        String keyword = MIRRORS[mi][1];
        String ext = MIRRORS[mi][2];

        // 从当前选择的镜像开始依次尝试
        int start = selectedMirror;
        for (int attempt = 0; attempt < DOWNLOAD_MIRRORS.length; attempt++) {
            int mirrorIdx = (start + attempt) % DOWNLOAD_MIRRORS.length;
            String prefix = DOWNLOAD_MIRRORS[mirrorIdx][1];
            String tryUrl = (prefix == null) ? apiUrl : prefix + "/" + apiUrl; // 原生 GitHub API
            try {
                String result = tryFetchApi(tryUrl, keyword, ext);
                if (result != null) {
                    if (mirrorIdx != selectedMirror) {
                        System.out.println("[MpvNative] API 通过镜像 " + DOWNLOAD_MIRRORS[mirrorIdx][0] + " 获取成功");
                    }
                    return result;
                }
            } catch (IOException e) {
                System.out.println("[MpvNative] 镜像 " + DOWNLOAD_MIRRORS[mirrorIdx][0] + " API 失败: " + e.getMessage());
            }
        }
        return null;
    }

    private static String tryFetchApi(String apiUrl, String keyword, String ext) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "FFCraft-Mod/1.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        try (InputStream is = conn.getInputStream()) {
            String json = new String(is.readAllBytes());
            String key = "\"browser_download_url\":\"";
            int idx = 0;
            String best = null;
            while ((idx = json.indexOf(key, idx)) >= 0) {
                int start = idx + key.length();
                int end = json.indexOf('"', start);
                String url = json.substring(start, end);
                String lower = url.toLowerCase();
                if (lower.contains(keyword.toLowerCase()) && lower.endsWith(ext.toLowerCase())) {
                    return url;
                }
                if (lower.contains(keyword.toLowerCase())) best = url;
                idx = end;
            }
            return best;
        }
    }

    // ---- 文件下载 ----

    /** 低于该大小不值得分段（2MB 以下直接单线程） */
    private static final long PARALLEL_MIN_FILE = 2L * 1024 * 1024;
    /** 每段至少 4MB，最多 8 段并发 */
    private static final long PARALLEL_MIN_SEGMENT = 4L * 1024 * 1024;
    private static final int PARALLEL_MAX_SEGMENTS = 8;

    /**
     * 多线程分段下载：先探测服务器是否支持 HTTP Range（响应 206 + Content-Range），
     * 支持则分 N 段并发下载（GitHub 和多数镜像站都支持），否则回退单线程。
     */
    private static void downloadFile(String url, Path dest, Consumer<Integer> progress)
            throws IOException {
        // 手动跟随重定向：Java 自动重定向会丢弃 Range 头，必须先解析出最终 URL
        String finalUrl = resolveRedirects(url);

        long total = -1;
        HttpURLConnection probe = openConn(finalUrl, "bytes=0-0");
        try {
            if (probe.getResponseCode() == 206) {
                total = parseContentRangeTotal(probe.getHeaderField("Content-Range"));
            }
        } finally {
            probe.disconnect();
        }

        if (total >= PARALLEL_MIN_FILE) {
            System.out.println("[MpvNative] 服务器支持分段，多线程下载 (" + total / 1048576 + "MB)");
            downloadParallel(finalUrl, dest, total, progress);
        } else {
            downloadSequential(finalUrl, dest, progress);
        }
    }

    /** 手动跟随 HTTP 重定向链，返回最终 URL（302 → Location，相对地址用原 URL 解析） */
    private static String resolveRedirects(String url) throws IOException {
        HttpURLConnection conn = openConn(url, null);
        int code;
        String loc;
        try {
            code = conn.getResponseCode();
            loc = conn.getHeaderField("Location");
        } finally {
            conn.disconnect();
        }
        if (code >= 300 && code < 400 && loc != null) {
            return resolveRedirects(URI.create(url).resolve(loc).toString());
        }
        return url;
    }

    private static HttpURLConnection openConn(String url, String range) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "FFCraft-Mod/1.0");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(false);
        if (range != null) conn.setRequestProperty("Range", range);
        return conn;
    }

    /** 解析 Content-Range: bytes 0-0/12345 → 12345；解析失败返回 -1 */
    private static long parseContentRangeTotal(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 分段并发下载：文件预分配为 total 长度，每段一个线程写自己的偏移 */
    private static void downloadParallel(String url, Path dest, long total, Consumer<Integer> progress)
            throws IOException {
        int segments = (int) Math.max(2, Math.min(PARALLEL_MAX_SEGMENTS, total / PARALLEL_MIN_SEGMENT + 1));
        System.out.println("[MpvNative] 分段数=" + segments);

        AtomicLong done = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicReference<IOException> firstError = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(segments);
        CountDownLatch latch = new CountDownLatch(segments);

        try (FileChannel prealloc = FileChannel.open(dest, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            prealloc.position(total - 1);
            prealloc.write(ByteBuffer.wrap(new byte[]{0})); // 预分配，避免各段并发写时文件伸缩
        }

        for (int i = 0; i < segments; i++) {
            long start = total * i / segments;
            long end = (i == segments - 1) ? total - 1 : total * (i + 1) / segments - 1;
            final long segStart = start, segEnd = end;
            pool.submit(() -> {
                try {
                    downloadSegment(url, segStart, segEnd, total, dest, done, failed, progress);
                } catch (IOException e) {
                    if (firstError.compareAndSet(null, e)) failed.set(true);
                } finally {
                    latch.countDown();
                }
            });
        }
        pool.shutdown();
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.set(true);
        }
        pool.shutdownNow();

        if (failed.get()) {
            throw firstError.get() != null ? firstError.get() : new IOException("分段下载失败");
        }
        downloadProgress = 100;
        if (progress != null) progress.accept(100);
    }

    /** 下载字节区间 [start, end]，写入 dest 的对应偏移 */
    private static void downloadSegment(String url, long start, long end, long total, Path dest,
                                        AtomicLong done, AtomicBoolean failed, Consumer<Integer> progress)
            throws IOException {
        HttpURLConnection conn = openConn(url, "bytes=" + start + "-" + end);
        try (InputStream is = conn.getInputStream();
             ReadableByteChannel rbc = Channels.newChannel(is);
             FileChannel fch = FileChannel.open(dest, StandardOpenOption.WRITE)) {
            fch.position(start);
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            long expected = end - start + 1;
            long segDone = 0;
            while (segDone < expected) {
                // 任一段失败 → 其余段停止（避免无谓流量 + 文件损坏）
                if (failed.get() || Thread.currentThread().isInterrupted()) {
                    throw new IOException("下载被取消");
                }
                buf.clear();
                int n = rbc.read(buf);
                if (n < 0) break; // 服务器提前断开
                buf.flip();
                while (buf.hasRemaining()) fch.write(buf);
                segDone += n;
                int pct = (int) (done.addAndGet(n) * 100 / total);
                downloadProgress = pct;
                if (progress != null) progress.accept(pct);
            }
            if (segDone != expected) {
                throw new IOException("段 " + start + "-" + end + " 长度不符: " + segDone + "/" + expected);
            }
        }
    }

    /** 单线程下载（服务器不支持 Range 时回退） */
    private static void downloadSequential(String url, Path dest, Consumer<Integer> progress)
            throws IOException {
        HttpURLConnection conn = openConn(url, null);
        long total = conn.getContentLengthLong();
        try (ReadableByteChannel rbc = Channels.newChannel(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(dest.toFile())) {

            byte[] buf = new byte[8192];
            long downloaded = 0;
            int n;
            while ((n = rbc.read(java.nio.ByteBuffer.wrap(buf))) > 0) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (total > 0 && progress != null) {
                    int pct = (int) (downloaded * 100 / total);
                    downloadProgress = pct;
                    progress.accept(pct);
                }
            }
        }
    }

    // ---- 压缩包解压 ----

    private static void extractDll(Path archive, Path destDir) throws IOException {
        String dllName = getLibFileName();
        String archivePath = archive.toString().toLowerCase();
        String internalPath = MIRRORS[getMirrorIndex()][3]; // 压缩包内路径（"" 表示根目录）

        // 方法1: 系统 7z.exe（处理 .7z，最优先）
        if (archivePath.endsWith(".7z")) {
            String sevenZip = find7z();
            if (sevenZip != null) {
                Process p = new ProcessBuilder(sevenZip, "e", "-y",
                        "-o" + destDir.toString(), archive.toString(), internalPath)
                        .inheritIO().start();
                try {
                    int exit = p.waitFor();
                    if (exit == 0 && Files.exists(destDir.resolve(dllName))) return;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            // 7z 不可用时，尝试 tar.gz 的流式解压（某些 7z 文件可能用 zip 方式编码）
        }

        // 方法2: tar.gz (macOS) — Apache Commons Compress
        if (archivePath.endsWith(".tar.gz") || archivePath.endsWith(".tgz")) {
            try (InputStream fi = Files.newInputStream(archive);
                 InputStream gzi = new GzipCompressorInputStream(fi);
                 TarArchiveInputStream tis = new TarArchiveInputStream(gzi)) {
                extractFromArchiveStream(tis, internalPath, dllName, destDir);
                if (Files.exists(destDir.resolve(dllName))) return;
            }
        }

        // 方法3: zip / aar — 使用 Apache Commons Compress（支持嵌套 jar/aar）
        if (archivePath.endsWith(".zip") || archivePath.endsWith(".aar") || archivePath.endsWith(".jar")) {
            try (InputStream fi = Files.newInputStream(archive);
                 ZipArchiveInputStream zis = new ZipArchiveInputStream(fi)) {
                extractFromArchiveStream(zis, internalPath, dllName, destDir);
                if (Files.exists(destDir.resolve(dllName))) return;
            }
        }

        // 方法4: 纯 Java zip 回退
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(archive.toFile())) {
            // 先在 exact path 找
            var entry = zf.getEntry(internalPath);
            if (entry == null) {
                // 按文件名搜索
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    var e = entries.nextElement();
                    if (e.getName().endsWith(dllName) || e.getName().endsWith("/" + dllName)) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry != null) {
                try (InputStream is = zf.getInputStream(entry)) {
                    Files.copy(is, destDir.resolve(dllName));
                    return;
                }
            }
        }

        throw new IOException("无法解压，请手动提取 " + dllName + " 到 " + destDir);
    }

    /** 从 ArchiveInputStream 中查找并提取目标文件 */
    private static void extractFromArchiveStream(ArchiveInputStream ais, String targetName,
                                                  String dllName, Path destDir) throws IOException {
        ArchiveEntry entry;
        while ((entry = ais.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            // 匹配: exact path, endsWith, 或者内部路径匹配
            if (name.equals(targetName) || name.endsWith("/" + targetName)
                    || name.endsWith(dllName) || name.endsWith("/" + dllName)) {
                Files.copy(ais, destDir.resolve(dllName));
                return;
            }
        }
    }

    // ---- 本地压缩包扫描 ----

    /** 扫描游戏目录 + 缓存目录下的压缩包，找到匹配平台的自动解压 */
    private static boolean tryLocalArchive() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        String[] extsToTry = { MIRRORS[getMirrorIndex()][2], ".zip", ".7z", ".tar.gz" };

        // 精确匹配: mpv_download{ext}（下载失败残留）
        for (Path dir : new Path[]{ gameDir, NATIVE_DIR }) {
            if (!Files.isDirectory(dir)) continue;
            for (String e : extsToTry) {
                Path exact = dir.resolve("mpv_download" + e);
                if (Files.exists(exact)) {
                    try { extractDll(exact, NATIVE_DIR); Files.deleteIfExists(exact); } catch (Exception ex) { continue; }
                    if (loadFromCache()) return true;
                }
            }
        }

        // 模糊匹配: 目录下任何含有 "mpv" 的压缩包
        for (Path dir : new Path[]{ gameDir, NATIVE_DIR }) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.contains("mpv") && (n.endsWith(".7z") || n.endsWith(".zip")
                        || n.endsWith(".tar.gz") || n.endsWith(".aar") || n.endsWith(".tgz"));
            })) {
                for (Path p : ds) {
                    try {
                        extractDll(p, NATIVE_DIR);
                        Files.deleteIfExists(p);
                        if (loadFromCache()) return true;
                    } catch (Exception ex) { /* try next */ }
                }
            } catch (IOException ignored) {}
        }

        return false;
    }

    private static boolean loadFromCache() {
        Path libPath = getLibPath();
        if (Files.exists(libPath)) {
            try {
                System.setProperty("jna.library.path",
                        NATIVE_DIR.toString() + File.pathSeparator
                                + System.getProperty("jna.library.path", ""));
                mpv = Native.load(getLibName(), MpvNative.class);
                state = State.LOADED;
                statusMsg = "已加载本地 libmpv";
                System.out.println("[MpvNative] " + statusMsg);
                return true;
            } catch (UnsatisfiedLinkError e) {
                try { Files.deleteIfExists(libPath); } catch (IOException ignored) {}
            }
        }
        return false;
    }

    private static String find7z() {
        for (String p : new String[]{
                "C:\\Program Files\\7-Zip\\7z.exe",
                "C:\\Program Files (x86)\\7-Zip\\7z.exe",
                "/usr/bin/7z", "/usr/local/bin/7z"}) {
            if (new File(p).exists()) return p;
        }
        try {
            Process p = new ProcessBuilder("7z").start();
            p.destroy();
            return "7z";
        } catch (IOException ignored) {}
        return null;
    }
}

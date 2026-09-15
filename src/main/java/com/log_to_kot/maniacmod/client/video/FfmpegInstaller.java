package com.log_to_kot.maniacmod.client.video;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.*;
import java.util.function.Consumer;

/**
 * ══════════════════════════════════════════════════════════════
 *  АВТОМАТИЧНЕ ЗАВАНТАЖЕННЯ FFMPEG
 * ══════════════════════════════════════════════════════════════
 *
 *  Завантажує мінімальний статичний FFmpeg з офіційних білдів:
 *    Windows x64: ffmpeg-master-latest-win64-gpl.zip
 *    Linux  x64:  ffmpeg-master-latest-linux64-gpl.tar.xz
 *    macOS  arm:  ffmpeg-master-latest-linuxarm64-gpl.tar.xz
 *
 *  Джерело: https://github.com/BtbN/FFmpeg-Builds/releases
 *  Ліцензія: GPL — дозволено безкоштовно для будь-якого використання.
 *
 *  Розмір завантаження:
 *    Windows: ~45 MB (zip)
 *    Linux:   ~40 MB (tar.xz)
 *
 *  Де зберігається після встановлення:
 *    .minecraft/maniacmod/ffmpeg/ffmpeg[.exe]
 *
 *  Перевіряється при кожному старті мода:
 *    → якщо файл вже існує — пропускаємо
 *    → якщо ні — завантажуємо у фоні
 */
public class FfmpegInstaller {

    // ── Посилання на завантаження ─────────────────────────────────────────────

    private static final String BASE_URL =
        "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/";

    private static final String WIN_ZIP   = "ffmpeg-master-latest-win64-gpl.zip";
    private static final String LIN_XZ    = "ffmpeg-master-latest-linux64-gpl.tar.xz";
    private static final String MAC_XZ    = "ffmpeg-master-latest-linuxarm64-gpl.tar.xz";

    // ── Стан ─────────────────────────────────────────────────────────────────

    public enum State { IDLE, CHECKING, DOWNLOADING, EXTRACTING, READY, FAILED }

    private static volatile State   state       = State.IDLE;
    private static volatile int     progress    = 0;   // 0–100
    private static volatile String  statusMsg   = "";
    private static volatile String  errorMsg    = "";
    private static volatile boolean available   = false;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Перевіряє наявність FFmpeg.
     * Якщо не знайдено — запускає завантаження у фоновому потоці.
     *
     * @param onReady  викликається коли FFmpeg готовий (може бути null)
     */
    public static void ensureAvailable(Runnable onReady) {
        if (available) { if (onReady != null) onReady.run(); return; }
        if (state == State.DOWNLOADING || state == State.EXTRACTING) return;

        new Thread(() -> doInstall(onReady), "FfmpegInstaller").start();
    }

    public static State  getState()    { return state; }
    public static int    getProgress() { return progress; }
    public static String getStatus()   { return statusMsg; }
    public static String getError()    { return errorMsg; }
    public static boolean isAvailable(){ return available; }

    /** Шлях до виконуваного файлу FFmpeg (або null якщо не встановлено) */
    public static String getFfmpegPath() {
        Path p = getInstallDir().resolve(exeName());
        return (Files.exists(p) && p.toFile().canExecute()) ? p.toAbsolutePath().toString() : null;
    }

    // ── Встановлення ─────────────────────────────────────────────────────────

    private static void doInstall(Runnable onReady) {
        state = State.CHECKING;
        statusMsg = "Перевірка FFmpeg...";

        // 1. Спочатку перевіряємо чи вже є ffmpeg у нашій папці
        Path localPath = getInstallDir().resolve(exeName());
        if (Files.exists(localPath) && localPath.toFile().canExecute()) {
            ManiacMod.LOGGER.info("[FFmpeg] Вже встановлено: {}", localPath);
            markReady(onReady);
            return;
        }

        // 2. Перевіряємо системний PATH
        if (checkSystemFFmpeg()) {
            ManiacMod.LOGGER.info("[FFmpeg] Знайдено в системному PATH");
            markReady(onReady);
            return;
        }

        // 3. Завантажуємо
        state = State.DOWNLOADING;
        statusMsg = "Завантаження FFmpeg...";
        ManiacMod.LOGGER.info("[FFmpeg] Починаємо завантаження...");

        try {
            Files.createDirectories(getInstallDir());
            Files.createDirectories(getTempDir());

            String archiveName  = archiveName();
            Path   archivePath  = getTempDir().resolve(archiveName);
            String downloadUrl  = BASE_URL + archiveName;

            downloadFile(downloadUrl, archivePath);

            state = State.EXTRACTING;
            statusMsg = "Розпаковка FFmpeg...";
            progress = 0;

            if (isWindows()) {
                extractFromZip(archivePath, localPath);
            } else {
                extractFromTarXz(archivePath, localPath);
            }

            // Зробити виконуваним на Unix
            if (!isWindows()) localPath.toFile().setExecutable(true);

            // Видалити архів
            Files.deleteIfExists(archivePath);

            if (Files.exists(localPath)) {
                ManiacMod.LOGGER.info("[FFmpeg] Успішно встановлено: {}", localPath);
                markReady(onReady);
            } else {
                throw new IOException("Файл FFmpeg не знайдено після розпаковки");
            }

        } catch (Exception e) {
            state    = State.FAILED;
            errorMsg = e.getMessage();
            ManiacMod.LOGGER.error("[FFmpeg] Помилка встановлення: {}", e.getMessage());
        }
    }

    // ── Завантаження файлу з прогресом ───────────────────────────────────────

    private static void downloadFile(String urlStr, Path dest) throws IOException {
        statusMsg = "Завантаження: " + urlStr.substring(urlStr.lastIndexOf('/') + 1);
        ManiacMod.LOGGER.info("[FFmpeg] Завантаження з {}", urlStr);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "ManiacMod/3.0 FFmpeg-Downloader");

        // Follow redirects (GitHub uses redirects)
        conn.setInstanceFollowRedirects(true);
        int responseCode = conn.getResponseCode();

        // Manual redirect handling for https→https
        if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
            responseCode == 307 || responseCode == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(location).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "ManiacMod/3.0");
        }

        long totalBytes = conn.getContentLengthLong();
        long downloaded = 0;

        try (InputStream in  = new BufferedInputStream(conn.getInputStream(), 65536);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest), 65536)) {

            byte[] buf = new byte[65536];
            int    len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                downloaded += len;
                if (totalBytes > 0) {
                    progress = (int)(downloaded * 100L / totalBytes);
                    statusMsg = String.format("Завантаження FFmpeg... %d MB / %d MB",
                        downloaded / 1_048_576, totalBytes / 1_048_576);
                }
            }
        } finally {
            conn.disconnect();
        }

        ManiacMod.LOGGER.info("[FFmpeg] Завантажено {} MB", downloaded / 1_048_576);
    }

    // ── ZIP розпаковка (Windows) ──────────────────────────────────────────────

    private static void extractFromZip(Path zipPath, Path targetExe) throws IOException {
        ManiacMod.LOGGER.info("[FFmpeg] Розпаковка ZIP...");
        long total = Files.size(zipPath);
        long read  = 0;

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipPath), 65536))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                // Шукаємо ffmpeg.exe у будь-якій папці архіву
                if (name.endsWith("/ffmpeg.exe") || name.equals("ffmpeg.exe")) {
                    ManiacMod.LOGGER.info("[FFmpeg] Знайдено: {}", name);
                    try (OutputStream out = new BufferedOutputStream(
                            Files.newOutputStream(targetExe), 65536)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            out.write(buf, 0, len);
                            read += len;
                            progress = (int) Math.min(99, read * 100 / Math.max(1, entry.getSize()));
                        }
                    }
                    progress = 100;
                    zis.closeEntry();
                    return;
                }
                zis.closeEntry();
            }
        }
        throw new IOException("ffmpeg.exe не знайдено в архіві");
    }

    // ── tar.xz розпаковка (Linux/macOS) ──────────────────────────────────────
    // Не покладаємось на зовнішні утиліти — використовуємо Apache Commons Compress
    // який вже є в classpath через Minecraft/Forge (вони використовують його самі).

    private static void extractFromTarXz(Path tarXzPath, Path targetExe) throws IOException {
        ManiacMod.LOGGER.info("[FFmpeg] Розпаковка tar.xz...");

        // Apache Commons Compress є в Forge/Minecraft classpath
        try {
            Class<?> xzIS    = Class.forName("org.apache.commons.compress.compressors.xz.XZCompressorInputStream");
            Class<?> tarIS   = Class.forName("org.apache.commons.compress.archivers.tar.TarArchiveInputStream");
            Class<?> tarEntry = Class.forName("org.apache.commons.compress.archivers.tar.TarArchiveEntry");

            InputStream raw = new BufferedInputStream(Files.newInputStream(tarXzPath), 65536);

            // XZCompressorInputStream(raw)
            InputStream xz = (InputStream) xzIS.getConstructor(InputStream.class).newInstance(raw);

            // TarArchiveInputStream(xz)
            Object tar = tarIS.getConstructor(InputStream.class).newInstance(xz);

            java.lang.reflect.Method getNextEntry = tarIS.getMethod("getNextTarEntry");
            java.lang.reflect.Method getName      = tarEntry.getMethod("getName");

            Object entry;
            while ((entry = getNextEntry.invoke(tar)) != null) {
                String name = (String) getName.invoke(entry);
                if (name.endsWith("/ffmpeg") || name.equals("ffmpeg")) {
                    ManiacMod.LOGGER.info("[FFmpeg] Знайдено: {}", name);
                    try (OutputStream out = new BufferedOutputStream(
                            Files.newOutputStream(targetExe), 65536)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = ((InputStream) tar).read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                    ((Closeable) tar).close();
                    xz.close(); raw.close();
                    progress = 100;
                    return;
                }
            }
            ((Closeable) tar).close();
            xz.close(); raw.close();

        } catch (ClassNotFoundException e) {
            // Fallback: shell tar якщо є
            ManiacMod.LOGGER.warn("[FFmpeg] Commons Compress не знайдено, спробуємо shell tar...");
            extractWithShellTar(tarXzPath, targetExe);
            return;
        } catch (Exception e) {
            throw new IOException("Помилка розпаковки tar.xz: " + e.getMessage(), e);
        }
        throw new IOException("ffmpeg не знайдено в архіві tar.xz");
    }

    private static void extractWithShellTar(Path tarXzPath, Path targetExe) throws IOException {
        Path tmpDir = getTempDir().resolve("ffmpeg_extract");
        Files.createDirectories(tmpDir);

        ProcessBuilder pb = new ProcessBuilder(
            "tar", "-xJf", tarXzPath.toAbsolutePath().toString(),
            "--wildcards", "*/ffmpeg",
            "--strip-components=2",
            "-C", tmpDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Знайти ffmpeg у tmpDir
        try (var walk = Files.walk(tmpDir)) {
            walk.filter(p -> p.getFileName().toString().equals("ffmpeg"))
                .findFirst()
                .ifPresent(found -> {
                    try { Files.copy(found, targetExe, StandardCopyOption.REPLACE_EXISTING); }
                    catch (IOException ex) { ManiacMod.LOGGER.error("[FFmpeg] Copy failed: {}", ex.getMessage()); }
                });
        }

        // Cleanup
        try (var walk = Files.walk(tmpDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }

        if (!Files.exists(targetExe)) throw new IOException("shell tar не зміг розпакувати ffmpeg");
        progress = 100;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean checkSystemFFmpeg() {
        try {
            Process p = new ProcessBuilder(exeName(), "-version")
                .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private static void markReady(Runnable onReady) {
        available = true;
        state     = State.READY;
        statusMsg = "FFmpeg готовий!";
        progress  = 100;
        if (onReady != null) {
            Minecraft.getInstance().execute(onReady);
        }
    }

    private static Path getInstallDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("maniacmod").resolve("ffmpeg");
    }

    private static Path getTempDir() {
        return getInstallDir().resolve("tmp");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isArm() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("aarch64") || arch.contains("arm");
    }

    private static String exeName()      { return isWindows() ? "ffmpeg.exe" : "ffmpeg"; }

    private static String archiveName() {
        if (isWindows()) return WIN_ZIP;
        if (isArm())     return MAC_XZ;
        return LIN_XZ;
    }
}

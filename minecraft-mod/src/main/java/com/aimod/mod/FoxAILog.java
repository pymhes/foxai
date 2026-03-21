package com.aimod.mod;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * FoxAI — Oyun içi log sistemi
 *
 * Yazdığı dosya: .minecraft/logs/foxai.log
 * Eski log:      .minecraft/logs/foxai_prev.log  (her oturum başında)
 *
 * Kullanım:
 *   FoxAILog.cmd("!ai ağaç kes");           // Gelen komut
 *   FoxAILog.action("mine", "oak_log x5");  // Çalıştırılan aksiyon
 *   FoxAILog.api(url, 200, 340);            // API isteği
 *   FoxAILog.craft("axe", true, "3x planks + 2x stick"); // Craft sonucu
 *   FoxAILog.info("FoxAI spawn edildi");    // Genel bilgi
 *   FoxAILog.warn("Ağaç bulunamadı");       // Uyarı
 *   FoxAILog.error("JSON parse hatası", e); // Hata + stack trace
 */
public class FoxAILog {

    private static final String LOG_FILE  = "logs/foxai.log";
    private static final String PREV_FILE = "logs/foxai_prev.log";
    private static final int    MAX_BYTES = 2 * 1024 * 1024; // 2 MB

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss");

    // Asenkron yazma kuyruğu — oyun tick'ini bloklamaz
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(2000);
    private static volatile boolean running = false;
    private static Thread writerThread;

    // ── Başlat ────────────────────────────────────────────────────────────

    public static synchronized void init() {
        if (running) return;
        running = true;

        try {
            // Eski logu yedekle
            Path log  = Path.of(LOG_FILE);
            Path prev = Path.of(PREV_FILE);
            if (Files.exists(log) && Files.size(log) > 0) {
                Files.move(log, prev, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(log.getParent());
        } catch (IOException e) {
            AiPlayerMod.LOGGER.warn("[FoxAILog] Log klasörü hazırlanamadı: {}", e.getMessage());
        }

        writerThread = new Thread(FoxAILog::writerLoop, "FoxAI-Log-Writer");
        writerThread.setDaemon(true);
        writerThread.start();

        info("════════════════════════════════════════");
        info("FoxAI Log Başladı — " +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        info("Log dosyası: " + Path.of(LOG_FILE).toAbsolutePath());
        info("════════════════════════════════════════");
    }

    public static void shutdown() {
        running = false;
        if (writerThread != null) writerThread.interrupt();
    }

    // ── Log Metodları ─────────────────────────────────────────────────────

    /** Oyuncudan gelen komut */
    public static void cmd(String player, String message, boolean isConversation) {
        String mode = isConversation ? "SOHBET" : "KOMUT";
        write("CMD", "§" + player + " → [" + mode + "] " + message);
    }

    /** API isteği sonucu */
    public static void api(String url, int statusCode, long ms, String reply, int actionCount) {
        String status = (statusCode >= 200 && statusCode < 300) ? "OK" : "HATA";
        write("API", status + " " + statusCode + " (" + ms + "ms) → "
            + actionCount + " aksiyon | Cevap: " + truncate(reply, 80));
    }

    /** API bağlantı hatası */
    public static void apiError(String url, String errorMsg) {
        write("API", "BAĞLANTI HATASI → " + errorMsg);
    }

    /** Çalıştırılan aksiyon */
    public static void action(String type, String detail) {
        write("ACT", type.toUpperCase() + " → " + detail);
    }

    /** Aksiyon tamamlandı / zaman aşımı */
    public static void actionDone(String type, boolean success, int ticks) {
        String r = success ? "✓ TAMAM" : "✗ TIMEOUT";
        write("ACT", type.toUpperCase() + " " + r + " (" + ticks + " tick = " + (ticks/20) + "sn)");
    }

    /** Craft işlemi */
    public static void craft(String toolType, boolean success, String detail) {
        String r = success ? "✓" : "✗";
        write("CRAFT", r + " " + toolType + " → " + detail);
    }

    /** Envanter durumu */
    public static void inventory(String summary) {
        write("INV", summary);
    }

    /** Genel bilgi */
    public static void info(String msg) {
        write("INFO", msg);
    }

    /** Uyarı */
    public static void warn(String msg) {
        write("WARN", msg);
    }

    /** Hata — mesaj */
    public static void error(String msg) {
        write("ERR ", msg);
    }

    /** Hata — mesaj + exception */
    public static void error(String msg, Throwable t) {
        write("ERR ", msg + " → " + t.getClass().getSimpleName() + ": " + t.getMessage());
        // Stack trace'in ilk 4 satırını yaz
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(4, st.length); i++) {
            write("    ", "  at " + st[i]);
        }
    }

    /** Hayatta kalma durumu değişikliği */
    public static void survival(String state, String detail) {
        write("SRV", state + " → " + detail);
    }

    /** Görev zinciri adımı */
    public static void task(String taskName, String step, String detail) {
        write("TASK", "[" + taskName + "] " + step + " → " + detail);
    }

    // ── İç Metotlar ───────────────────────────────────────────────────────

    private static void write(String level, String msg) {
        String line = "[" + LocalDateTime.now().format(FMT) + "] "
                    + "[" + level + "] " + msg;
        // Konsola da yaz (sadece WARN ve ERR)
        if (level.startsWith("WARN") || level.startsWith("ERR")) {
            AiPlayerMod.LOGGER.warn("[FoxAI] {}", msg);
        }
        // Asenkron kuyruğa ekle (doluysa eski kaydı at, oyunu bloklamayalım)
        if (!QUEUE.offer(line)) {
            QUEUE.poll();
            QUEUE.offer(line);
        }
    }

    private static void writerLoop() {
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(LOG_FILE, true), StandardCharsets.UTF_8))) {

            while (running || !QUEUE.isEmpty()) {
                try {
                    String line = QUEUE.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (line == null) continue;

                    bw.write(line);
                    bw.newLine();

                    // Kuyrukta biriken varsa hepsini toplu yaz
                    String extra;
                    int batch = 0;
                    while ((extra = QUEUE.poll()) != null && batch++ < 50) {
                        bw.write(extra);
                        bw.newLine();
                    }
                    bw.flush();

                    // Boyut kontrolü — 2 MB aşarsa eski logu sil, yeniye başla
                    if (new File(LOG_FILE).length() > MAX_BYTES) {
                        bw.write("[" + LocalDateTime.now().format(FMT) + "] [INFO] --- Log rotate edildi ---");
                        bw.newLine();
                        bw.flush();
                        // Rotate: mevcut dosyayı prev'e taşı
                        try {
                            Files.move(Path.of(LOG_FILE), Path.of(PREV_FILE),
                                StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            bw.flush();
        } catch (IOException e) {
            AiPlayerMod.LOGGER.error("[FoxAILog] Dosyaya yazılamadı: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

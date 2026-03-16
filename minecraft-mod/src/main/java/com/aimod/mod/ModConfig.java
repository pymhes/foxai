package com.aimod.mod;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class ModConfig {
    private static final String CONFIG_FILE = "config/aiplayermod.properties";
    private static Properties props = new Properties();

    static {
        loadConfig();
    }

    private static void loadConfig() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            saveDefaults(file);
        }
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            AiPlayerMod.LOGGER.warn("[AI Player Mod] Yapılandırma yüklenemedi, varsayılanlar kullanılıyor.", e);
            setDefaults();
        }
    }

    private static void setDefaults() {
        props.setProperty("enabled", "true");
        props.setProperty("triggerPrefix", "!ai");
        props.setProperty("apiUrl", "http://localhost:8080/api/mod/chat");
        props.setProperty("respondToAll", "false");
        props.setProperty("language", "tr");
    }

    private static void saveDefaults(File file) {
        setDefaults();
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, "AI Player Mod Configuration\n" +
                "enabled=true/false - Modu açar/kapatır\n" +
                "triggerPrefix=!ai - Chat komutlarının başlangıç eki\n" +
                "apiUrl=http://... - AI API adresi\n" +
                "respondToAll=true/false - Tüm chat mesajlarına cevap ver\n" +
                "language=tr/en - Yanıt dili");
        } catch (IOException e) {
            AiPlayerMod.LOGGER.warn("[AI Player Mod] Yapılandırma kaydedilemedi.", e);
        }
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(props.getProperty("enabled", "true"));
    }

    public static String getTriggerPrefix() {
        return props.getProperty("triggerPrefix", "!ai");
    }

    public static String getApiUrl() {
        return props.getProperty("apiUrl", "http://localhost:8080/api/mod/chat");
    }

    public static boolean isRespondToAll() {
        return Boolean.parseBoolean(props.getProperty("respondToAll", "false"));
    }

    public static String getLanguage() {
        return props.getProperty("language", "tr");
    }

    public static void reload() {
        loadConfig();
    }
}

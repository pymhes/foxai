package com.aimod.mod;

/**
 * Client-side önbellek.
 * FoxAIContextPacket geldiğinde güncellenir.
 * ChatEventHandler.buildContext() bunu okur.
 */
public class FoxAIContextCache {

    private static volatile String cachedContext = "";

    public static void set(String context) {
        cachedContext = context;
    }

    public static String get() {
        return cachedContext;
    }
}

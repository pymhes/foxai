package com.aimod.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * ActionExecutor — SADECE client-side görsel feedback.
 * Gerçek aksiyonlar FoxAIEntity.processAction() içinde server-side çalışır.
 * Bu sınıf sadece oyuncuya ne olduğunu gösterir.
 */
public class ActionExecutor {

    private static volatile boolean shouldStop = false;

    public static void executeActionsAsync(JsonArray actions) {
        shouldStop = false;
        // Artık sadece "dur" aksiyonu için kullanılıyor
        if (actions == null) return;
        Minecraft mc = Minecraft.getInstance();
        for (JsonElement el : actions) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            String type = a.has("type") ? a.get("type").getAsString() : "";
            if ("stop".equals(type) || "stop_follow".equals(type)) {
                shouldStop = true;
                mc.execute(() -> {
                    if (mc.player != null)
                        fox(mc.player, "🛑 tamam durdum.");
                });
            }
        }
    }

    public static void stop() {
        shouldStop = true;
    }

    private static void fox(LocalPlayer player, String msg) {
        player.displayClientMessage(Component.literal("§8[§aFox§8] §f" + msg), false);
    }
}

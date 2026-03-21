package com.aimod.mod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = AiPlayerMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChatEventHandler {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FoxAI-Chat-Worker");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean isBusy = false;

    // Sohbet için konuşma önekleri (lowercase)
    private static final String[] CONV_PREFIXES = {
        "fox ", "foxai ", "fox,", "foxai,", "hey fox", "hey foxai",
        "aga fox", "bro fox", "knk fox", "kanka fox"
    };

    @SubscribeEvent
    public static void onChatMessage(ClientChatEvent event) {
        if (!ModConfig.isEnabled()) return;

        String message = event.getMessage().trim();
        String prefix = ModConfig.getTriggerPrefix(); // !ai
        String lowerMsg = message.toLowerCase();

        boolean isCommand = false;
        boolean isConversation = false;
        String cleanMessage = message;

        // KOMUT modu: !ai ile başlıyor
        if (lowerMsg.startsWith(prefix.toLowerCase())) {
            isCommand = true;
            isConversation = false;
            cleanMessage = message.substring(prefix.length()).trim();
            event.setCanceled(true);
        }
        // SOHBET modu: "fox" veya "foxai" ile başlıyor
        else {
            for (String conv : CONV_PREFIXES) {
                if (lowerMsg.startsWith(conv)) {
                    isConversation = true;
                    cleanMessage = message.substring(conv.length()).trim();
                    event.setCanceled(true);
                    break;
                }
            }
            // "dur" veya "stop" — acil durdurma
            if (!isConversation && (lowerMsg.equals("dur") || lowerMsg.equals("stop") || lowerMsg.equals("bekle"))) {
                isCommand = true;
                cleanMessage = "dur";
                event.setCanceled(true);
            }
        }

        if (!isCommand && !isConversation) return;
        if (cleanMessage.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Acil dur komutu
        if (isCommand && (cleanMessage.equalsIgnoreCase("dur") || cleanMessage.equalsIgnoreCase("stop"))) {
            isBusy = false;
            // Server'daki FoxAI'ye dur komutu gönder
            JsonArray stopActions = new JsonArray();
            JsonObject s = new JsonObject();
            s.addProperty("type", "stop");
            stopActions.add(s);
            FoxAINetwork.CHANNEL.sendToServer(new ActionPacket(stopActions.toString(), player.getUUID()));
            fox(player, "§cDurdum. Ne var ne yok? 👀");
            return;
        }

        // Meşgulse sadece komutlarda beklet (sohbet herzaman cevap verir)
        if (isCommand && isBusy) {
            fox(player, "§7Bi dakka knk, şu işi bitiriyorum. (Durdurmak için: §eDUR§7)");
            return;
        }

        String playerName = player.getName().getString();
        String finalMessage = cleanMessage;
        final boolean finalIsConversation = isConversation;

        // Log: gelen komut
        FoxAILog.cmd(playerName, finalMessage, isConversation);

        // Düşünme animasyonu
        if (!isConversation) {
            fox(player, "§7💭 tamam bakıyorum...");
            isBusy = true;
        } else {
            fox(player, "§7💬 ...");
        }

        executor.submit(() -> {
            try {
                String context = buildContext(mc);
                List<String> mods = ModDetector.getLoadedMods();
                ApiClient.ModChatResponse response =
                    ApiClient.sendChatMessageFull(finalMessage, playerName, context, finalIsConversation, mods);

                mc.execute(() -> {
                    if (!finalIsConversation) isBusy = false;
                    if (mc.player == null) return;

                    // Duygu/emote göster
                    if (response.emotion != null) {
                        String emoteChar = switch (response.emotion) {
                            case "hyped", "excited" -> "§e🔥";
                            case "worried" -> "§c😰";
                            case "tired" -> "§7😴";
                            case "focused" -> "§b🎯";
                            default -> "§a😄";
                        };
                        // Emote ekranı
                        fox(mc.player, emoteChar + " §f" + response.reply);
                    } else {
                        fox(mc.player, "§e" + response.reply);
                    }

                    // Plan varsa göster
                    if (response.plan != null && !response.plan.isEmpty()) {
                        fox(mc.player, "§7📋 " + response.plan);
                    }

                    // Aksiyonlar — paketi server'a gönder (entity orada çalışır)
                    if (!finalIsConversation && response.understood
                            && response.actions != null && !response.actions.isEmpty()) {
                        fox(mc.player, "§7▶ " + response.actions.size() + " adım FoxAI'ye gönderiliyor...");
                        String json = response.actions.toString();
                        java.util.UUID uuid = mc.player.getUUID();
                        FoxAINetwork.CHANNEL.sendToServer(new ActionPacket(json, uuid));
                        // FoxSayPacket ile reply'ı da entity'ye söylet
                        FoxAINetwork.CHANNEL.sendToServer(new FoxSayPacket(response.reply, uuid));
                    }
                });

            } catch (Exception e) {
                isBusy = false;
                FoxAILog.error("Chat iş parçacığı hatası: " + e.getMessage(), e);
                AiPlayerMod.LOGGER.error("[FoxAI] Chat hatası: {}", e.getMessage(), e);
                mc.execute(() -> {
                    if (mc.player != null) {
                        fox(mc.player, "§cya bağlantı koptu knk, API çalışıyor mu? 😬");
                    }
                });
            }
        });
    }

    public static String buildContext(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return "";
        StringBuilder ctx = new StringBuilder();
        ctx.append("=== OYUN DURUMU ===\n");
        ctx.append("Konum: ").append(player.blockPosition()).append("\n");
        ctx.append("Can: ").append((int) player.getHealth()).append("/").append((int) player.getMaxHealth()).append("\n");
        ctx.append("Açlık: ").append(player.getFoodData().getFoodLevel()).append("/20\n");
        ctx.append("Exp Seviye: ").append(player.experienceLevel).append("\n");

        var mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty())
            ctx.append("Ana El: ").append(mainHand.getDisplayName().getString())
               .append(" x").append(mainHand.getCount()).append("\n");

        // Zırh
        StringBuilder armorStr = new StringBuilder();
        player.getArmorSlots().forEach(s -> {
            if (!s.isEmpty()) armorStr.append(s.getDisplayName().getString()).append(", ");
        });
        if (!armorStr.isEmpty()) ctx.append("Zırh: ").append(armorStr).append("\n");

        // Hotbar
        ctx.append("Hotbar: ");
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty())
                ctx.append(stack.getDisplayName().getString()).append("x").append(stack.getCount()).append(", ");
        }
        ctx.append("\n");

        // Su kovası var mı?
        boolean hasWaterBucket = false;
        boolean hasLadder = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var st = player.getInventory().getItem(i);
            if (!st.isEmpty()) {
                String id = st.getDescriptionId().toLowerCase();
                if (id.contains("water_bucket")) hasWaterBucket = true;
                if (id.contains("ladder")) hasLadder = true;
            }
        }
        ctx.append("Su kovası: ").append(hasWaterBucket ? "VAR" : "yok").append("\n");
        ctx.append("Merdiven: ").append(hasLadder ? "VAR" : "yok").append("\n");

        if (mc.level != null) {
            ctx.append("Boyut: ").append(mc.level.dimension().location().getPath()).append("\n");
            ctx.append("Gece: ").append(mc.level.isNight()).append("\n");
            ctx.append("Yağmur: ").append(mc.level.isRaining()).append("\n");

            var nearbyPlayers = mc.level.players().stream()
                .filter(p -> p != player && p.distanceTo(player) < 20)
                .map(p -> p.getName().getString()).toList();
            if (!nearbyPlayers.isEmpty())
                ctx.append("Yakındaki Oyuncular: ").append(String.join(", ", nearbyPlayers)).append("\n");
        }

        // Yüklü modlar
        String modContext = ModDetector.buildModContext();
        if (!modContext.isEmpty()) ctx.append("\n").append(modContext);

        // FoxAI'nin dünya algısı (server'dan gelen gerçek zamanlı veri)
        String foxCtx = FoxAIContextCache.get();
        if (foxCtx != null && !foxCtx.isEmpty()) {
            ctx.append("\n").append(foxCtx);
        }

        return ctx.toString();
    }

    private static void fox(LocalPlayer player, String msg) {
        player.displayClientMessage(Component.literal("§8[§aFox§8] §f" + msg), false);
    }
}

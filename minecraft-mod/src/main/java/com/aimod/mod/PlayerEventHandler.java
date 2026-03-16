package com.aimod.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = AiPlayerMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerEventHandler {

    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FoxAI-Monitor");
            t.setDaemon(true);
            return t;
        });

    private static long lastHealthWarning = 0;
    private static long lastHungerWarning = 0;
    private static long lastNightWarning = 0;
    private static long lastIdleComment = 0;

    // Pasif izleme — her 5 saniyede bir çalışır
    static {
        scheduler.scheduleAtFixedRate(PlayerEventHandler::checkPlayerStatus,
            15, 5, TimeUnit.SECONDS);
    }

    private static void checkPlayerStatus() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !ModConfig.isEnabled()) return;

        long now = System.currentTimeMillis();

        // Düşük can uyarısı (30 saniyede bir tekrar)
        float health = player.getHealth();
        if (health <= 5f && (now - lastHealthWarning) > 30_000) {
            lastHealthWarning = now;
            mc.execute(() -> fox(player, "§cYA KANKA CAN ÇOK AZ! " + (int)health + " can kaldı, bi yere saklanıyoruz hemen!"));
        }

        // Açlık uyarısı (60 saniyede bir tekrar)
        int food = player.getFoodData().getFoodLevel();
        if (food <= 3 && (now - lastHungerWarning) > 60_000) {
            lastHungerWarning = now;
            mc.execute(() -> fox(player, "§eBro ne zamandır yedin ya? Sprint de yapamıyoruz artık, bi şeyler ye knk 🍖"));
        }

        // Gece uyarısı
        if (mc.level.isNight() && mc.level.getLightEmission(player.blockPosition()) < 4
                && (now - lastNightWarning) > 120_000) {
            lastNightWarning = now;
            mc.execute(() -> fox(player, "§7Bro karanlık çok, creeper bait oluyoruz. Meşale koyalım ya da içeri girelim 👀"));
        }

        // Rastgele idle yorumlar (3 dakikada bir)
        if ((now - lastIdleComment) > 180_000) {
            lastIdleComment = now;
            String[] comments = {
                "vallaha ne yapacaksak yapalım, burada bekliyorum 😂",
                "ya şu manzara fena değil actually",
                "bro kaç saattir oynuyoruz, hayat bu mu 😅",
                "knk bi elmas görürsem haber veririm",
                "şu an çok miner modundayım, kafayı yedim",
                "ya creeper sesi duysam yayarım seni, hazır ol"
            };
            int idx = (int)(Math.random() * comments.length);
            String comment = comments[idx];
            mc.execute(() -> fox(player, "§7" + comment));
        }
    }

    private static void fox(LocalPlayer player, String msg) {
        player.displayClientMessage(
            Component.literal("§8[§aFoxAI§8] " + msg), false
        );
    }
}

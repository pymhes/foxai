package com.aimod.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActionExecutor {
    private static final Logger LOGGER = LogManager.getLogger(AiPlayerMod.MOD_ID);
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FoxAI-Executor");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean shouldStop = false;

    public static void executeActionsAsync(JsonArray actions) {
        shouldStop = false;
        executor.submit(() -> executeActions(actions));
    }

    public static void executeActions(JsonArray actions) {
        if (actions == null || actions.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();

        for (JsonElement el : actions) {
            if (shouldStop) { mc.execute(() -> releaseAllKeys(mc)); break; }
            if (!el.isJsonObject()) continue;
            JsonObject action = el.getAsJsonObject();
            String type = action.has("type") ? action.get("type").getAsString() : "";
            try {
                mc.execute(() -> executeAction(mc, type, action));
                Thread.sleep(getDelay(type));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        mc.execute(() -> releaseAllKeys(mc));
    }

    private static int getDelay(String type) {
        return switch (type.toLowerCase()) {
            case "think", "warn", "say", "whisper" -> 600;
            case "mine" -> 2500;
            case "place", "place_water", "place_ladder" -> 700;
            case "safe_fall" -> 1000;
            case "craft", "smelt" -> 1800;
            case "wait" -> 3000;
            case "move", "sprint" -> 1000;
            case "attack" -> 500;
            case "eat" -> 2200;
            case "emote" -> 800;
            default -> 350;
        };
    }

    private static void releaseAllKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private static void executeAction(Minecraft mc, String type, JsonObject a) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        String target  = str(a, "target");
        String dir     = str(a, "direction");
        String extra   = str(a, "extra");
        String message = str(a, "message");
        String pattern = str(a, "pattern");
        int qty    = integer(a, "quantity", 1);
        int steps  = integer(a, "steps", 5);
        int radius = integer(a, "radius", 5);

        LOGGER.debug("[FoxAI] {} target={} dir={} qty={}", type, target, dir, qty);

        switch (type.toLowerCase()) {

            case "think" -> fox(player, "§b💭 " + coalesce(message, extra, "..."));
            case "warn"  -> fox(player, "§c⚠ " + coalesce(message, extra, "Dikkat!"));

            case "move"   -> executeMove(mc, player, dir, steps, target);
            case "sprint" -> executeSprint(mc, player, dir, steps);
            case "look"   -> executeLook(player, dir, target);
            case "jump"   -> {
                for (int i = 0; i < Math.min(qty, 5); i++) player.jumpFromGround();
                fox(player, "🦘 hop!");
            }
            case "sneak" -> {
                boolean on = !"off".equalsIgnoreCase(extra);
                player.setShiftKeyDown(on);
                mc.options.keyShift.setDown(on);
                fox(player, on ? "🐾 sinsi mod ON" : "🐾 sinsi mod OFF");
            }
            case "stop", "stop_follow" -> {
                shouldStop = true;
                releaseAllKeys(mc);
                fox(player, "🛑 tamam durdum.");
            }

            case "say" -> {
                String msg = coalesce(message, target, extra);
                if (msg != null && mc.getConnection() != null) mc.getConnection().sendChat(msg);
            }
            case "whisper" -> {
                if (target != null && message != null && mc.getConnection() != null)
                    mc.getConnection().sendChat("/msg " + target + " " + message);
            }
            case "emote" -> executeEmote(mc, player, coalesce(extra, target, "wave"));

            case "attack"  -> executeAttack(mc, player, target, qty);
            case "mine"    -> executeMine(mc, player, target, qty, radius);
            case "place"   -> executePlace(mc, player, target, dir, pattern);

            // ── FALL DAMAGE KORUMASI ─────────────────────────────────────────
            case "safe_fall"    -> executeSafeFall(mc, player, extra);
            case "place_water"  -> executePlaceWater(mc, player, dir);
            case "place_ladder" -> executePlaceLadder(mc, player, dir, qty);

            case "craft"  -> fox(player, "⚒️ craft: §e" + coalesce(target, "?") + " §7x" + qty);
            case "smelt"  -> fox(player, "🔥 ergitiliyor: §e" + coalesce(target, "?") + " §7x" + qty);
            case "eat"    -> executeEat(mc, player, target);
            case "follow", "guard" -> executeFollow(mc, player, target, radius);
            case "equip"  -> executeEquip(player, target);
            case "unequip"-> fox(player, "🎒 çıkarıldı: " + coalesce(target, "?"));

            case "drop_item"    -> { player.drop(false); fox(player, "🗑️ atıldı"); }
            case "pickup_item"  -> fox(player, "🤏 toplanıyor...");
            case "open_chest"   -> { mc.options.keyUse.setDown(true); fox(player, "📦 sandık açılıyor..."); }
            case "close_chest"  -> { if (mc.screen != null) mc.setScreen(null); fox(player, "📦 kapandı."); }
            case "use_item"     -> { mc.options.keyUse.setDown(true); fox(player, "🖱️ kullanılıyor..."); }
            case "interact"     -> { mc.options.keyUse.setDown(true); fox(player, "🤝 etkileşim..."); }
            case "fish"         -> { mc.options.keyUse.setDown(true); fox(player, "🎣 olta atıldı!"); }
            case "farm"         -> { mc.options.keyUse.setDown(true); fox(player, "🌾 tarla işleniyor..."); }
            case "sleep"        -> { mc.options.keyUse.setDown(true); fox(player, "💤 uyku aranıyor..."); }
            case "sort_inventory" -> fox(player, "🎒 envanter düzenleniyor...");
            case "wait"         -> fox(player, "⏳ bekliyorum...");
            case "celebrate"    -> { player.jumpFromGround(); fox(player, "🎉 " + coalesce(extra, "gg ez win!")); }
            case "shield"       -> {
                boolean on = !"off".equalsIgnoreCase(extra);
                mc.options.keyUse.setDown(on);
                fox(player, on ? "🛡️ kalkan kaldırıldı" : "🛡️ kalkan indirildi");
            }
        }
    }

    // ── HAREKET ──────────────────────────────────────────────────────────────

    private static void executeMove(Minecraft mc, LocalPlayer player, String dir, int steps, String target) {
        if (dir == null && target != null) {
            fox(player, "🚶 §e" + target + " §7hedefine gidiyorum...");
            mc.options.keyUp.setDown(true);
            return;
        }
        applyDirKey(mc, dir);
        fox(player, "🚶 " + dirTR(dir) + " §7→ " + steps + " adım");
    }

    private static void executeSprint(Minecraft mc, LocalPlayer player, String dir, int steps) {
        player.setSprinting(true);
        mc.options.keySprint.setDown(true);
        applyDirKey(mc, dir);
        fox(player, "💨 koşuyorum: " + dirTR(dir));
    }

    private static void applyDirKey(Minecraft mc, String dir) {
        if (dir == null) { mc.options.keyUp.setDown(true); return; }
        switch (dir.toLowerCase()) {
            case "north","kuzey","forward","ileri" -> mc.options.keyUp.setDown(true);
            case "south","güney","back","geri"     -> mc.options.keyDown.setDown(true);
            case "east","doğu","right","sağ"       -> mc.options.keyRight.setDown(true);
            case "west","batı","left","sol"        -> mc.options.keyLeft.setDown(true);
            case "up","yukarı"                     -> { if (mc.player!=null) mc.player.jumpFromGround(); }
            default                                -> mc.options.keyUp.setDown(true);
        }
    }

    private static void executeLook(LocalPlayer player, String dir, String target) {
        if (dir == null) { fox(player, "👀 " + coalesce(target, "etraf") + "a bakıyorum"); return; }
        float yaw = player.getYRot(), pitch = 0f;
        switch (dir.toLowerCase()) {
            case "north","kuzey" -> yaw = 180f;
            case "south","güney" -> yaw = 0f;
            case "east","doğu"   -> yaw = -90f;
            case "west","batı"   -> yaw = 90f;
            case "up","yukarı"   -> pitch = -70f;
            case "down","aşağı"  -> pitch = 70f;
        }
        player.setYRot(yaw);
        player.setXRot(pitch);
        fox(player, "👀 " + dirTR(dir));
    }

    // ── FALL DAMAGE KORUMASI ─────────────────────────────────────────────────

    private static void executeSafeFall(Minecraft mc, LocalPlayer player, String method) {
        if ("water".equalsIgnoreCase(method)) {
            executePlaceWater(mc, player, "down");
        } else if ("ladder".equalsIgnoreCase(method)) {
            executePlaceLadder(mc, player, "down", 5);
        } else {
            // Envantere bak, en iyi seçeneği kullan
            boolean hasWater = hasItem(player, "water_bucket");
            boolean hasLadder = hasItem(player, "ladder");
            if (hasWater) {
                executePlaceWater(mc, player, "down");
            } else if (hasLadder) {
                executePlaceLadder(mc, player, "down", 5);
            } else {
                fox(player, "§cknk ne su kovası var ne merdiven! Yavaşça in, sprint yapma 😬");
                // Yavaş düşüş için shift bas
                player.setShiftKeyDown(true);
                mc.options.keyShift.setDown(true);
            }
        }
    }

    private static void executePlaceWater(Minecraft mc, LocalPlayer player, String dir) {
        // Su kovasını hotbar'a al
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getDescriptionId().toLowerCase().contains("water_bucket")) {
                player.getInventory().selected = i;
                fox(player, "🪣 su kovası yerleştiriliyor — fall damage yok! 💧");
                mc.options.keyUse.setDown(true);
                return;
            }
        }
        fox(player, "§csu kovası hotbar'da bulunamadı knk 😅");
    }

    private static void executePlaceLadder(Minecraft mc, LocalPlayer player, String dir, int qty) {
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getDescriptionId().toLowerCase().contains("ladder")) {
                player.getInventory().selected = i;
                fox(player, "🪜 merdiven yerleştiriliyor x" + qty + " — güvenli iniş! 👍");
                // Duvara bak ve merdiven koy
                player.setXRot(80f);
                mc.options.keyUse.setDown(true);
                return;
            }
        }
        fox(player, "§cmerdiven yok envanterda, craft edelim mi? 🪵");
    }

    private static boolean hasItem(LocalPlayer player, String partialId) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var st = player.getInventory().getItem(i);
            if (!st.isEmpty() && st.getDescriptionId().toLowerCase().contains(partialId)) return true;
        }
        return false;
    }

    // ── SAVAŞ ────────────────────────────────────────────────────────────────

    private static void executeAttack(Minecraft mc, LocalPlayer player, String target, int qty) {
        Level level = mc.level;
        if (level == null) return;
        AABB box = new AABB(player.blockPosition()).inflate(6);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box,
            e -> e != player && !(e instanceof Player));

        if (target != null && !target.equals("nearest") && !target.equals("all")) {
            entities = entities.stream().filter(e ->
                e.getType().getDescriptionId().toLowerCase().contains(target.toLowerCase()) ||
                e.getName().getString().toLowerCase().contains(target.toLowerCase())).toList();
        }

        entities = entities.stream()
            .sorted((a, b) -> Double.compare(a.distanceTo(player), b.distanceTo(player)))
            .limit(Math.max(qty, 1)).toList();

        if (!entities.isEmpty()) {
            for (var e : entities) {
                mc.gameMode.attack(player, e);
                fox(player, "⚔️ vurdum: §e" + e.getName().getString());
            }
        } else {
            fox(player, "⚔️ hedef bulunamadı: " + coalesce(target, "düşman"));
        }
    }

    // ── KAZMA ────────────────────────────────────────────────────────────────

    private static void executeMine(Minecraft mc, LocalPlayer player, String block, int qty, int radius) {
        fox(player, "⛏️ kazıyorum: §e" + coalesce(block, "blok") + " §7x" + qty);
        mc.options.keyAttack.setDown(true);
    }

    // ── YERLEŞTIRME ──────────────────────────────────────────────────────────

    private static void executePlace(Minecraft mc, LocalPlayer player, String block, String dir, String pattern) {
        fox(player, "🧱 koyuyorum: §e" + coalesce(block, "blok")
            + (pattern != null ? " §7[" + pattern + "]" : ""));
        mc.options.keyUse.setDown(true);
    }

    // ── YEMEK ────────────────────────────────────────────────────────────────

    private static void executeEat(Minecraft mc, LocalPlayer player, String food) {
        int bestSlot = -1, bestNutrition = -1;
        for (int i = 0; i < 9; i++) {
            var st = player.getInventory().getItem(i);
            if (st.isEmpty() || !st.getItem().isEdible()) continue;
            if (food != null && !food.equals("best_food") &&
                !st.getDescriptionId().toLowerCase().contains(food.toLowerCase())) continue;
            var fp = st.getItem().getFoodProperties(st, player);
            int n = fp != null ? fp.getNutrition() : 1;
            if (n > bestNutrition) { bestNutrition = n; bestSlot = i; }
        }
        if (bestSlot >= 0) {
            player.getInventory().selected = bestSlot;
            mc.options.keyUse.setDown(true);
            fox(player, "🍖 yiyorum... afiyet olsun knk 😄");
        } else {
            fox(player, "🍖 yemek bulamadım, millet ne yer bu dünyada 😅");
        }
    }

    // ── TAKİP ────────────────────────────────────────────────────────────────

    private static void executeFollow(Minecraft mc, LocalPlayer player, String target, int radius) {
        fox(player, "👣 " + (target != null && !target.equals("player") ? target + "ı" : "seni") + " takip ediyorum");
        mc.options.keyUp.setDown(true);
    }

    // ── KUŞANMA ──────────────────────────────────────────────────────────────

    private static void executeEquip(LocalPlayer player, String item) {
        if (item == null) { fox(player, "🎒 ne kuşanayım söyle"); return; }
        String search = switch (item.toLowerCase()) {
            case "best_weapon" -> "sword";
            case "best_pickaxe" -> "pickaxe";
            case "best_axe" -> "axe";
            case "best_armor" -> "chestplate";
            default -> item;
        };
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var st = player.getInventory().getItem(i);
            if (!st.isEmpty() && (
                st.getDescriptionId().toLowerCase().contains(search.toLowerCase()) ||
                st.getDisplayName().getString().toLowerCase().contains(search.toLowerCase()))) {
                player.getInventory().selected = Math.min(i, 8);
                fox(player, "⚔️ kuşandım: §e" + st.getDisplayName().getString() + " §7✓");
                return;
            }
        }
        fox(player, "🎒 bulamadım: " + item + " 🤷");
    }

    // ── EMOTE ────────────────────────────────────────────────────────────────

    private static void executeEmote(Minecraft mc, LocalPlayer player, String emote) {
        switch (emote.toLowerCase()) {
            case "dance" -> {
                player.jumpFromGround();
                fox(player, "💃 dans ediyorum! get down knk");
            }
            case "wave" -> fox(player, "👋 selamlar, ben FoxAI! 🦊");
            case "bow"  -> {
                player.setShiftKeyDown(true);
                fox(player, "🙇 saygılarımı sunarım efendim 😂");
            }
            default     -> fox(player, "🎭 " + emote);
        }
    }

    // ── YARDIMCI ─────────────────────────────────────────────────────────────

    private static void fox(LocalPlayer player, String msg) {
        player.displayClientMessage(Component.literal("§8[§aFox§8] §f" + msg), false);
    }

    private static String str(JsonObject obj, String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : null;
    }

    private static int integer(JsonObject obj, String key, int def) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsInt() : def;
    }

    private static String coalesce(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private static String dirTR(String d) {
        if (d == null) return "ileri";
        return switch (d.toLowerCase()) {
            case "north" -> "kuzey"; case "south" -> "güney";
            case "east"  -> "doğu";  case "west"  -> "batı";
            case "up"    -> "yukarı";case "down"  -> "aşağı";
            default -> d;
        };
    }
}

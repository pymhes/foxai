package com.aimod.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.monster.Monster;

import java.util.*;

/**
 * FoxAI Görev Zinciri Sistemi
 *
 * Komutlar:
 *   fox.taskSystem.start(TaskType.BUILD_BASE)
 *   fox.taskSystem.start(TaskType.MINING_TRIP)
 *   fox.taskSystem.start(TaskType.FARMING)
 *   fox.taskSystem.start(TaskType.GEAR_UP)
 *   fox.taskSystem.start(TaskType.STORAGE_SETUP)
 *   fox.taskSystem.start(TaskType.EXPLORE)
 *   fox.taskSystem.start(TaskType.STOCK_RESOURCES)
 *   fox.taskSystem.cancel()
 */
public class FoxAITaskSystem {

    // ── Görev Tipleri ──────────────────────────────────────────────────────
    public enum TaskType {
        BUILD_BASE,       // Ev/üs kur
        MINING_TRIP,      // Madene git, elmas ara, dön
        FARMING,          // Tarım döngüsü
        GEAR_UP,          // Ekipman seti yap
        STORAGE_SETUP,    // Sandık kur, eşyaları depola
        EXPLORE,          // Keşif turu
        STOCK_RESOURCES,  // Kaynak stoklama
        DEFEND_BASE       // Savunma çevresi
    }

    // ── Alt Görev Adımları ─────────────────────────────────────────────────
    public enum StepType {
        // Genel
        IDLE, WAIT, MOVE_TO, ANNOUNCE,
        // Kaynak
        COLLECT_WOOD, COLLECT_STONE, COLLECT_FOOD,
        // Craft
        CRAFT_AXE, CRAFT_PICKAXE, CRAFT_SWORD, CRAFT_ARMOR,
        CRAFT_CHEST, CRAFT_FURNACE, CRAFT_SEEDS,
        // İnşaat
        BUILD_WALLS, BUILD_ROOF, BUILD_FLOOR, PLACE_DOOR,
        PLACE_CHEST, PLACE_FURNACE, PLACE_BED,
        BUILD_FENCE, BUILD_WATCHTOWER,
        // Madencilik
        MINE_DOWN, MINE_TUNNELS, COLLECT_ORES, RETURN_HOME,
        // Tarım
        TILL_SOIL, PLANT_SEEDS, WAIT_HARVEST, HARVEST_CROPS,
        // Depolama
        SORT_INVENTORY, DEPOSIT_ITEMS,
        // Keşif
        EXPLORE_NORTH, EXPLORE_EAST, EXPLORE_SOUTH, EXPLORE_WEST, REPORT_FINDINGS,
        // Savunma
        BUILD_PERIMETER, LIGHT_UP_AREA
    }

    // ── Görev Adımı ────────────────────────────────────────────────────────
    public static class TaskStep {
        public final StepType type;
        public final String description;
        public int timer = 0;
        public boolean done = false;
        public Object data; // Ek veri (BlockPos, int vs.)

        public TaskStep(StepType type, String description) {
            this.type = type;
            this.description = description;
        }

        public TaskStep(StepType type, String description, Object data) {
            this.type = type;
            this.description = description;
            this.data = data;
        }
    }

    // ── State ──────────────────────────────────────────────────────────────
    private final FoxAIEntity fox;
    private TaskType currentTask = null;
    private final Queue<TaskStep> steps = new LinkedList<>();
    private TaskStep activeStep = null;
    private int globalTimer = 0;
    private boolean active = false;
    private BlockPos homeBase = null; // Üs konumu
    private BlockPos farmPos = null;  // Tarla konumu
    private BlockPos chestPos = null; // Ana sandık

    public FoxAITaskSystem(FoxAIEntity fox) {
        this.fox = fox;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public boolean isActive() { return active; }
    public TaskType getCurrentTask() { return currentTask; }

    /** Görev başlat */
    public void start(TaskType type) {
        cancel();
        currentTask = type;
        active = true;
        globalTimer = 0;
        homeBase = fox.blockPosition();
        buildSteps(type);
        fox.broadcastToNearby("§6📋 Görev başladı: §e" + taskName(type));
    }

    /** Görevi iptal et */
    public void cancel() {
        active = false;
        steps.clear();
        activeStep = null;
        currentTask = null;
        fox.getNavigation().stop();
        fox.broadcastToNearby("§c🛑 Görev iptal edildi.");
    }

    /** Her tick çağrılır (FoxAIEntity.tick() içinden) */
    public void tick() {
        if (!active) return;
        globalTimer++;

        // Aktif adım yoksa sıradan al
        if (activeStep == null) {
            activeStep = steps.poll();
            if (activeStep == null) {
                // Tüm adımlar bitti
                fox.broadcastToNearby("§a✅ Görev tamamlandı: §e" + taskName(currentTask) + " §7(" + (globalTimer/20) + "s)");
                active = false;
                currentTask = null;
                return;
            }
            activeStep.timer = 0;
            fox.broadcastToNearby("§7▶ " + activeStep.description);
        }

        activeStep.timer++;

        // Timeout kontrolü (her adım max 600 tick = 30sn)
        if (activeStep.timer > getStepTimeout(activeStep.type)) {
            fox.broadcastToNearby("§c⏱ Adım zaman aşımı: " + activeStep.description + " — atlıyorum");
            activeStep = null;
            return;
        }

        // Adımı çalıştır
        boolean done = executeStep(activeStep);
        if (done) {
            activeStep = null;
        }
    }

    // ── Görev Adımları Builder ─────────────────────────────────────────────

    private void buildSteps(TaskType type) {
        switch (type) {

            case BUILD_BASE -> {
                // Aşama 1: Hazırlık
                steps.add(new TaskStep(StepType.ANNOUNCE, "🏗️ Üs inşaatı başlıyor! Önce malzeme topluyorum..."));
                steps.add(new TaskStep(StepType.COLLECT_WOOD, "🌲 32 log topluyorum...", 32));
                steps.add(new TaskStep(StepType.CRAFT_AXE, "⚒️ Balta craft ediyorum"));
                steps.add(new TaskStep(StepType.COLLECT_WOOD, "🌲 Daha fazla odun topluyorum (64)...", 64));
                steps.add(new TaskStep(StepType.COLLECT_STONE, "⛏️ Taş topluyorum (32)...", 32));
                steps.add(new TaskStep(StepType.CRAFT_PICKAXE, "⚒️ Kazma craft ediyorum"));
                // Aşama 2: Temel inşaat
                steps.add(new TaskStep(StepType.ANNOUNCE, "🏠 Temel atıyorum..."));
                steps.add(new TaskStep(StepType.BUILD_FLOOR, "🧱 Zemin döşüyorum"));
                steps.add(new TaskStep(StepType.BUILD_WALLS, "🧱 Duvarları örüyorum (5x5x3)"));
                steps.add(new TaskStep(StepType.BUILD_ROOF, "🧱 Çatıyı kapatıyorum"));
                steps.add(new TaskStep(StepType.PLACE_DOOR, "🚪 Kapı koyuyorum"));
                // Aşama 3: İç donatım
                steps.add(new TaskStep(StepType.CRAFT_CHEST, "📦 Sandık craft ediyorum"));
                steps.add(new TaskStep(StepType.CRAFT_FURNACE, "🔥 Fırın craft ediyorum"));
                steps.add(new TaskStep(StepType.PLACE_CHEST, "📦 Sandığı yerleştiriyorum"));
                steps.add(new TaskStep(StepType.PLACE_FURNACE, "🔥 Fırını yerleştiriyorum"));
                // Aşama 4: Güvenlik
                steps.add(new TaskStep(StepType.BUILD_FENCE, "🪵 Çevre çiti çekiyorum"));
                steps.add(new TaskStep(StepType.LIGHT_UP_AREA, "🔦 Meşaleler koyuyorum"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "§a🏠 Üs tamamlandı! Artık bir evimiz var kanka!"));
            }

            case MINING_TRIP -> {
                steps.add(new TaskStep(StepType.ANNOUNCE, "⛏️ Madene gidiyorum! Elmas arayacağım..."));
                steps.add(new TaskStep(StepType.CRAFT_PICKAXE, "⚒️ Kazma hazırlıyorum"));
                steps.add(new TaskStep(StepType.CRAFT_SWORD, "⚔️ Kılıç hazırlıyorum (mağara hayvanları için)"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "🕳️ Aşağı iniyorum (Y=12 hedef)..."));
                steps.add(new TaskStep(StepType.MINE_DOWN, "⛏️ Mağaraya iniyorum...", -40)); // Y=-40 (elmas seviyesi)
                steps.add(new TaskStep(StepType.MINE_TUNNELS, "⛏️ Tünel kazıyorum (branch mining)..."));
                steps.add(new TaskStep(StepType.COLLECT_ORES, "💎 Cevherleri topluyorum..."));
                steps.add(new TaskStep(StepType.ANNOUNCE, "🏃 Eve dönüyorum!"));
                steps.add(new TaskStep(StepType.RETURN_HOME, "🏠 Eve dönüyorum..."));
                steps.add(new TaskStep(StepType.SORT_INVENTORY, "🎒 Envanteri düzenliyorum"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "§a✅ Madencilik bitti! Bak ne buldum:"));
            }

            case FARMING -> {
                steps.add(new TaskStep(StepType.ANNOUNCE, "🌾 Tarım döngüsü başlıyor!"));
                steps.add(new TaskStep(StepType.CRAFT_SEEDS, "🌱 Tohumları hazırlıyorum"));
                steps.add(new TaskStep(StepType.TILL_SOIL, "🪛 Toprak işliyorum (5x5 tarla)"));
                steps.add(new TaskStep(StepType.PLANT_SEEDS, "🌱 Tohumları ekiyorum"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "⏳ Mahsul büyüsün diye bekliyorum..."));
                steps.add(new TaskStep(StepType.WAIT_HARVEST, "🌾 Büyümesi bekleniyor...", 2400)); // 2 dk
                steps.add(new TaskStep(StepType.HARVEST_CROPS, "🌾 Hasat yapıyorum!"));
                steps.add(new TaskStep(StepType.PLANT_SEEDS, "🌱 Yeniden ekiyorum (döngü)"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "§a✅ Hasat tamamlandı!"));
            }

            case GEAR_UP -> {
                steps.add(new TaskStep(StepType.ANNOUNCE, "⚔️ Ekipman hazırlığı başlıyor!"));
                steps.add(new TaskStep(StepType.COLLECT_WOOD, "🌲 Odun topluyorum...", 16));
                steps.add(new TaskStep(StepType.COLLECT_STONE, "🪨 Taş topluyorum...", 24));
                steps.add(new TaskStep(StepType.CRAFT_AXE, "🪓 Balta yapıyorum"));
                steps.add(new TaskStep(StepType.CRAFT_PICKAXE, "⛏️ Kazma yapıyorum"));
                steps.add(new TaskStep(StepType.CRAFT_SWORD, "⚔️ Kılıç yapıyorum"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "⛏️ Demir arıyorum (zırh için)..."));
                steps.add(new TaskStep(StepType.MINE_TUNNELS, "⛏️ Demir cevheri arıyorum..."));
                steps.add(new TaskStep(StepType.CRAFT_ARMOR, "🛡️ Zırh yapıyorum (demir varsa)"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "§a✅ Ekipman hazır! Artık savaşa hazırım kanka!"));
            }

            case STORAGE_SETUP -> {
                steps.add(new TaskStep(StepType.ANNOUNCE, "📦 Depolama sistemi kuruyorum!"));
                steps.add(new TaskStep(StepType.COLLECT_WOOD, "🌲 Odun topluyorum (sandıklar için)...", 16));
                steps.add(new TaskStep(StepType.CRAFT_CHEST, "📦 Sandıklar craft ediyorum (x4)"));
                steps.add(new TaskStep(StepType.PLACE_CHEST, "📦 Sandıkları yerleştiriyorum"));
                steps.add(new TaskStep(StepType.SORT_INVENTORY, "🎒 Eşyaları kategorilere ayırıyorum"));
                steps.add(new TaskStep(StepType.DEPOSIT_ITEMS, "📥 Eşyaları sandıklara koyuyorum"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "§a✅ Depolama hazır! Düzenli bir ev gibiyiz artık."));
            }

            case EXPLORE -> {
                steps.add(new TaskStep(StepType.ANNOUNCE, "🗺️ Keşif turu başlıyor! Etrafı tanıyacağım..."));
                steps.add(new TaskStep(StepType.CRAFT_SWORD, "⚔️ Silah hazırlıyorum"));
                steps.add(new TaskStep(StepType.EXPLORE_NORTH, "🧭 Kuzeye gidiyorum (64 blok)..."));
                steps.add(new TaskStep(StepType.EXPLORE_EAST, "🧭 Doğuya gidiyorum (64 blok)..."));
                steps.add(new TaskStep(StepType.EXPLORE_SOUTH, "🧭 Güneye gidiyorum (64 blok)..."));
                steps.add(new TaskStep(StepType.EXPLORE_WEST, "🧭 Batıya gidiyorum (64 blok)..."));
                steps.add(new TaskStep(StepType.RETURN_HOME, "🏠 Eve dönüyorum..."));
                steps.add(new TaskStep(StepType.REPORT_FINDINGS, "📊 Keşif raporu hazırlıyorum..."));
            }

            case STOCK_RESOURCES -> {
                steps.add(new TaskStep(StepType.ANNOUNCE, "📦 Kaynak stoklama başlıyor! Hedef: odun x64, taş x64, yemek x32"));
                steps.add(new TaskStep(StepType.CRAFT_AXE, "🪓 Balta hazırlıyorum"));
                steps.add(new TaskStep(StepType.COLLECT_WOOD, "🌲 64 log topluyorum...", 64));
                steps.add(new TaskStep(StepType.CRAFT_PICKAXE, "⛏️ Kazma hazırlıyorum"));
                steps.add(new TaskStep(StepType.COLLECT_STONE, "🪨 64 taş topluyorum...", 64));
                steps.add(new TaskStep(StepType.COLLECT_FOOD, "🍖 Yemek topluyorum (avlanma/hasat)..."));
                steps.add(new TaskStep(StepType.SORT_INVENTORY, "🎒 Stokları düzenliyorum"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "§a✅ Stoklar hazır!"));
            }

            case DEFEND_BASE -> {
                steps.add(new TaskStep(StepType.ANNOUNCE, "🛡️ Savunma sistemi kuruyorum!"));
                steps.add(new TaskStep(StepType.COLLECT_WOOD, "🌲 Çit için odun topluyorum...", 32));
                steps.add(new TaskStep(StepType.COLLECT_STONE, "🪨 Duvar için taş topluyorum...", 32));
                steps.add(new TaskStep(StepType.BUILD_FENCE, "🪵 Çevre çiti çekiyorum (10x10)"));
                steps.add(new TaskStep(StepType.BUILD_WATCHTOWER, "🗼 Gözetleme kulesi yapıyorum"));
                steps.add(new TaskStep(StepType.LIGHT_UP_AREA, "🔦 Çevreyi aydınlatıyorum (meşaleler)"));
                steps.add(new TaskStep(StepType.ANNOUNCE, "§a✅ Savunma tamamlandı! Mob sızmaz artık."));
            }
        }
    }

    // ── Adım Çalıştırıcı ──────────────────────────────────────────────────

    private boolean executeStep(TaskStep step) {
        return switch (step.type) {

            case ANNOUNCE -> {
                // Sadece ilk tick'te mesaj ver
                if (step.timer == 1) fox.broadcastToNearby(step.description);
                yield true;
            }

            case WAIT -> {
                int waitTicks = step.data instanceof Integer d ? d : 60;
                yield step.timer >= waitTicks;
            }

            case COLLECT_WOOD -> {
                int needed = step.data instanceof Integer d ? d : 16;
                int have = fox.countItem("log") + fox.countItem("planks") / 4;
                if (have >= needed) yield true;

                // En yakın ağacı bul ve kes
                BlockPos tree = fox.findBlock("log", 24);
                if (tree == null) {
                    fox.broadcastToNearby("§c🌲 Ağaç bulunamadı! (radius: 24)");
                    yield true; // Atlayarak devam et
                }
                double dist = fox.distanceToSqr(Vec3.atCenterOf(tree));
                if (dist > 6) {
                    if (!fox.getNavigation().isInProgress())
                        fox.getNavigation().moveTo(tree.getX()+0.5, tree.getY(), tree.getZ()+0.5, 1.2);
                    yield false;
                }
                if (!fox.level().isClientSide()) {
                    fox.level().destroyBlock(tree, true, fox);
                    fox.pickupNearbyItems();
                    int newHave = fox.countItem("log");
                    if (step.timer % 20 == 0)
                        fox.broadcastToNearby("🌲 Log: " + newHave + "/" + needed);
                }
                yield fox.countItem("log") >= needed;
            }

            case COLLECT_STONE -> {
                int needed = step.data instanceof Integer d ? d : 16;
                int have = fox.countItem("cobblestone") + fox.countItem("stone");
                if (have >= needed) yield true;

                BlockPos stone = fox.findBlock("stone", 16);
                if (stone == null) stone = fox.findBlock("cobblestone", 16);
                if (stone == null) { fox.broadcastToNearby("§c🪨 Taş bulunamadı!"); yield true; }

                double dist = fox.distanceToSqr(Vec3.atCenterOf(stone));
                if (dist > 6) {
                    if (!fox.getNavigation().isInProgress())
                        fox.getNavigation().moveTo(stone.getX()+0.5, stone.getY(), stone.getZ()+0.5, 1.2);
                    yield false;
                }
                if (!fox.level().isClientSide()) {
                    fox.level().destroyBlock(stone, true, fox);
                    fox.pickupNearbyItems();
                }
                yield (fox.countItem("cobblestone") + fox.countItem("stone")) >= needed;
            }

            case COLLECT_FOOD -> {
                // Hayvan avla veya tarla hasat et
                if (fox.countItem("bread") + fox.countItem("cooked") + fox.countItem("apple") >= 16)
                    yield true;
                // Yakında hayvan var mı?
                var animals = fox.level().getEntitiesOfClass(
                    net.minecraft.world.entity.animal.Animal.class,
                    new AABB(fox.blockPosition()).inflate(16));
                if (!animals.isEmpty()) {
                    var animal = animals.get(0);
                    if (fox.distanceTo(animal) > 3) {
                        fox.getNavigation().moveTo(animal, 1.2);
                        yield false;
                    }
                    fox.doHurtTarget(animal);
                }
                yield step.timer > 200;
            }

            case CRAFT_AXE -> { fox.ensureTool("axe"); yield fox.equipBestTool("axe"); }
            case CRAFT_PICKAXE -> { fox.ensureTool("pickaxe"); yield fox.equipBestTool("pickaxe"); }
            case CRAFT_SWORD -> { fox.ensureTool("sword"); yield fox.equipBestTool("sword"); }

            case CRAFT_ARMOR -> {
                // Demir varsa zırh yap
                if (fox.countItem("iron_ingot") >= 8) {
                    if (!fox.level().isClientSide()) {
                        fox.giveItem(new ItemStack(Items.IRON_CHESTPLATE, 1));
                        fox.consumeItem("iron_ingot", 8);
                        fox.broadcastToNearby("🛡️ Demir zırh yaptım!");
                    }
                } else {
                    fox.broadcastToNearby("§c🛡️ Yeterli demir yok, zırh yapılamadı.");
                }
                yield true;
            }

            case CRAFT_CHEST -> {
                if (fox.countItem("planks") < 8 && fox.countItem("log") >= 2) {
                    fox.consumeItem("log", 2);
                    fox.giveItem(new ItemStack(Items.OAK_PLANKS, 8));
                }
                if (fox.countItem("planks") >= 8 && !fox.level().isClientSide()) {
                    fox.consumeItem("planks", 8);
                    fox.giveItem(new ItemStack(Items.CHEST, 1));
                    fox.broadcastToNearby("📦 Sandık yaptım!");
                }
                yield true;
            }

            case CRAFT_FURNACE -> {
                if (fox.countItem("cobblestone") + fox.countItem("stone") >= 8 && !fox.level().isClientSide()) {
                    fox.consumeItem("cobblestone", 8);
                    fox.giveItem(new ItemStack(Items.FURNACE, 1));
                    fox.broadcastToNearby("🔥 Fırın yaptım!");
                }
                yield true;
            }

            case CRAFT_SEEDS -> {
                // Tohum var mı? Yoksa elinde tahta varsa çubuk → tohum simüle et
                if (fox.countItem("wheat_seeds") < 10 && !fox.level().isClientSide()) {
                    fox.giveItem(new ItemStack(Items.WHEAT_SEEDS, 16));
                    fox.broadcastToNearby("🌱 Tohumları hazırladım!");
                }
                yield true;
            }

            case BUILD_FLOOR -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                int placed = 0;
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        BlockPos fp = base.offset(x, -1, z);
                        if (fox.level().isEmptyBlock(fp) || fox.level().getBlockState(fp).getBlock() == Blocks.GRASS_BLOCK) {
                            fox.level().setBlockAndUpdate(fp, Blocks.OAK_PLANKS.defaultBlockState());
                            placed++;
                        }
                    }
                }
                fox.broadcastToNearby("🧱 Zemin döşendi (" + placed + " blok)");
                yield true;
            }

            case BUILD_WALLS -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                int placed = 0;
                for (int y = 0; y <= 3; y++) {
                    for (int x = -2; x <= 2; x++) {
                        for (int z = -2; z <= 2; z++) {
                            // Sadece kenarlar
                            if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;
                            // Kapı yeri boş bırak (güney duvarı orta)
                            if (z == 2 && x == 0 && (y == 0 || y == 1)) continue;
                            BlockPos wp = base.offset(x, y, z);
                            if (fox.level().isEmptyBlock(wp)) {
                                fox.level().setBlockAndUpdate(wp, Blocks.OAK_LOG.defaultBlockState());
                                placed++;
                            }
                        }
                    }
                }
                fox.broadcastToNearby("🧱 Duvarlar örüldü (" + placed + " blok)");
                yield true;
            }

            case BUILD_ROOF -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                int placed = 0;
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        BlockPos rp = base.offset(x, 4, z);
                        if (fox.level().isEmptyBlock(rp)) {
                            fox.level().setBlockAndUpdate(rp, Blocks.OAK_PLANKS.defaultBlockState());
                            placed++;
                        }
                    }
                }
                fox.broadcastToNearby("🧱 Çatı kapatıldı (" + placed + " blok)");
                yield true;
            }

            case PLACE_DOOR -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                BlockPos doorPos = base.offset(0, 0, 2);
                fox.level().setBlockAndUpdate(doorPos, Blocks.OAK_DOOR.defaultBlockState());
                fox.broadcastToNearby("🚪 Kapı yerleştirildi!");
                yield true;
            }

            case PLACE_CHEST -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                BlockPos cp = base.offset(1, 0, -1);
                if (fox.level().isEmptyBlock(cp)) {
                    fox.level().setBlockAndUpdate(cp, Blocks.CHEST.defaultBlockState());
                    chestPos = cp;
                    fox.broadcastToNearby("📦 Sandık yerleştirildi!");
                }
                yield true;
            }

            case PLACE_FURNACE -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                BlockPos fp = base.offset(-1, 0, -1);
                if (fox.level().isEmptyBlock(fp)) {
                    fox.level().setBlockAndUpdate(fp, Blocks.FURNACE.defaultBlockState());
                    fox.broadcastToNearby("🔥 Fırın yerleştirildi!");
                }
                yield true;
            }

            case BUILD_FENCE -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                int radius = 6;
                int placed = 0;
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                        // Giriş kapısı bırak
                        if (z == radius && (x == 0 || x == 1)) continue;
                        BlockPos fp = base.offset(x, 0, z);
                        if (fox.level().isEmptyBlock(fp)) {
                            fox.level().setBlockAndUpdate(fp, Blocks.OAK_FENCE.defaultBlockState());
                            placed++;
                        }
                    }
                }
                fox.broadcastToNearby("🪵 Çit çekildi (" + placed + " blok)");
                yield true;
            }

            case BUILD_WATCHTOWER -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                BlockPos tower = base.offset(4, 0, 4);
                // 5 blok yükseklik
                for (int y = 0; y < 5; y++) {
                    fox.level().setBlockAndUpdate(tower.above(y), Blocks.OAK_LOG.defaultBlockState());
                }
                // Üst platform
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        fox.level().setBlockAndUpdate(tower.offset(dx, 5, dz), Blocks.OAK_PLANKS.defaultBlockState());
                fox.broadcastToNearby("🗼 Gözetleme kulesi tamamlandı!");
                yield true;
            }

            case LIGHT_UP_AREA -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase : fox.blockPosition();
                int torchCount = 0;
                // Her 4 blokta bir meşale koy
                for (int x = -8; x <= 8; x += 4) {
                    for (int z = -8; z <= 8; z += 4) {
                        BlockPos tp = base.offset(x, 0, z);
                        // Zemin bul
                        while (!fox.level().isEmptyBlock(tp) && tp.getY() < 320) tp = tp.above();
                        if (fox.level().isEmptyBlock(tp) && !fox.level().isEmptyBlock(tp.below())) {
                            fox.level().setBlockAndUpdate(tp, Blocks.TORCH.defaultBlockState());
                            torchCount++;
                        }
                    }
                }
                fox.broadcastToNearby("🔦 " + torchCount + " meşale yerleştirildi!");
                yield true;
            }

            case MINE_DOWN -> {
                int targetY = step.data instanceof Integer d ? d : -40;
                if (fox.blockPosition().getY() <= targetY) yield true;
                // Aşağı in
                BlockPos below = fox.blockPosition().below();
                if (!fox.level().isClientSide() && !fox.level().isEmptyBlock(below)) {
                    fox.level().destroyBlock(below, true, fox);
                }
                fox.getNavigation().moveTo(fox.getX(), targetY, fox.getZ(), 1.0);
                yield fox.blockPosition().getY() <= targetY || step.timer > 400;
            }

            case MINE_TUNNELS -> {
                // Her yönde 16 blok tünel kaz
                if (step.timer < 40) {
                    // Kuzey
                    BlockPos north = fox.blockPosition().north(16);
                    fox.getNavigation().moveTo(north.getX(), fox.blockPosition().getY(), north.getZ(), 1.0);
                } else if (step.timer < 80) {
                    // Güney
                    BlockPos south = fox.blockPosition().south(16);
                    fox.getNavigation().moveTo(south.getX(), fox.blockPosition().getY(), south.getZ(), 1.0);
                }
                // Gördüğü cevherleri kır
                BlockPos ore = fox.findBlock("ore", 4);
                if (ore != null && !fox.level().isClientSide()) {
                    fox.level().destroyBlock(ore, true, fox);
                    fox.pickupNearbyItems();
                    fox.broadcastToNearby("💎 Cevher buldum: " + fox.level().getBlockState(ore).getBlock().getName().getString());
                }
                yield step.timer > 120;
            }

            case COLLECT_ORES -> {
                fox.pickupNearbyItems();
                fox.broadcastToNearby("💎 Toplanan: elmas=" + fox.countItem("diamond")
                    + " demir=" + fox.countItem("iron_ingot")
                    + " altın=" + fox.countItem("gold_ingot"));
                yield true;
            }

            case RETURN_HOME -> {
                BlockPos home = homeBase != null ? homeBase : fox.blockPosition();
                double dist = fox.distanceToSqr(Vec3.atCenterOf(home));
                if (dist < 9) yield true;
                if (!fox.getNavigation().isInProgress())
                    fox.getNavigation().moveTo(home.getX()+0.5, home.getY(), home.getZ()+0.5, 1.3);
                yield dist < 9 || step.timer > 400;
            }

            case TILL_SOIL -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = homeBase != null ? homeBase.offset(8, 0, 0) : fox.blockPosition().offset(8, 0, 0);
                farmPos = base;
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        BlockPos fp = base.offset(x, 0, z);
                        var state = fox.level().getBlockState(fp);
                        if (state.getBlock() == Blocks.GRASS_BLOCK || state.getBlock() == Blocks.DIRT) {
                            fox.level().setBlockAndUpdate(fp, Blocks.FARMLAND.defaultBlockState());
                        }
                    }
                }
                // Yanına su koy
                fox.level().setBlockAndUpdate(base.offset(2, 0, -1), Blocks.WATER.defaultBlockState());
                fox.broadcastToNearby("🪛 5x5 tarla hazırlandı!");
                yield true;
            }

            case PLANT_SEEDS -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = farmPos != null ? farmPos : fox.blockPosition();
                int planted = 0;
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        BlockPos sp = base.offset(x, 1, z);
                        BlockPos ground = base.offset(x, 0, z);
                        if (fox.level().isEmptyBlock(sp) &&
                            fox.level().getBlockState(ground).getBlock() == Blocks.FARMLAND) {
                            fox.level().setBlockAndUpdate(sp, Blocks.WHEAT.defaultBlockState());
                            planted++;
                        }
                    }
                }
                fox.broadcastToNearby("🌱 " + planted + " tohum ekildi!");
                yield true;
            }

            case WAIT_HARVEST -> {
                int wait = step.data instanceof Integer d ? d : 2400;
                if (step.timer % 400 == 0) {
                    fox.broadcastToNearby("⏳ Mahsul büyüyor... (" + (step.timer/20) + "/" + (wait/20) + "s)");
                }
                yield step.timer >= wait;
            }

            case HARVEST_CROPS -> {
                if (fox.level().isClientSide()) yield true;
                BlockPos base = farmPos != null ? farmPos : fox.blockPosition();
                int harvested = 0;
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        BlockPos cp = base.offset(x, 1, z);
                        var state = fox.level().getBlockState(cp);
                        if (state.getBlock() == Blocks.WHEAT) {
                            fox.level().destroyBlock(cp, true, fox);
                            harvested++;
                        }
                    }
                }
                fox.pickupNearbyItems();
                fox.broadcastToNearby("🌾 " + harvested + " mahsul toplandı! Buğday: " + fox.countItem("wheat"));
                yield true;
            }

            case SORT_INVENTORY -> {
                fox.broadcastToNearby("🎒 Envanter düzenleniyor...");
                // Basit düzenleme — stackleri birleştir (Minecraft otomatik yapar)
                yield true;
            }

            case DEPOSIT_ITEMS -> {
                if (chestPos == null) {
                    fox.broadcastToNearby("§c📦 Sandık konumu bilinmiyor!");
                    yield true;
                }
                double dist = fox.distanceToSqr(Vec3.atCenterOf(chestPos));
                if (dist > 4) {
                    fox.getNavigation().moveTo(chestPos.getX()+0.5, chestPos.getY(), chestPos.getZ()+0.5, 1.0);
                    yield false;
                }
                fox.broadcastToNearby("📥 Eşyaları sandığa koydum! (simülasyon)");
                yield true;
            }

            case EXPLORE_NORTH -> {
                BlockPos target = homeBase != null ? homeBase.north(64) : fox.blockPosition().north(64);
                if (!fox.getNavigation().isInProgress())
                    fox.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.2);
                // Giderken etrafı tara
                if (step.timer % 40 == 0) scanSurroundings();
                yield fox.distanceToSqr(Vec3.atCenterOf(target)) < 16 || step.timer > 300;
            }
            case EXPLORE_EAST -> {
                BlockPos target = homeBase != null ? homeBase.east(64) : fox.blockPosition().east(64);
                if (!fox.getNavigation().isInProgress())
                    fox.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.2);
                if (step.timer % 40 == 0) scanSurroundings();
                yield fox.distanceToSqr(Vec3.atCenterOf(target)) < 16 || step.timer > 300;
            }
            case EXPLORE_SOUTH -> {
                BlockPos target = homeBase != null ? homeBase.south(64) : fox.blockPosition().south(64);
                if (!fox.getNavigation().isInProgress())
                    fox.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.2);
                if (step.timer % 40 == 0) scanSurroundings();
                yield fox.distanceToSqr(Vec3.atCenterOf(target)) < 16 || step.timer > 300;
            }
            case EXPLORE_WEST -> {
                BlockPos target = homeBase != null ? homeBase.west(64) : fox.blockPosition().west(64);
                if (!fox.getNavigation().isInProgress())
                    fox.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.2);
                if (step.timer % 40 == 0) scanSurroundings();
                yield fox.distanceToSqr(Vec3.atCenterOf(target)) < 16 || step.timer > 300;
            }

            case REPORT_FINDINGS -> {
                fox.broadcastToNearby("§b📊 Keşif Raporu:");
                fox.broadcastToNearby("§7  Konum: " + fox.blockPosition());
                fox.broadcastToNearby("§7  Yakın cevher: " + (fox.findBlock("ore", 32) != null ? "VAR" : "yok"));
                fox.broadcastToNearby("§7  Yakın mağara: " + (fox.findBlock("cave", 32) != null ? "VAR" : "yok"));
                fox.broadcastToNearby("§7  Düşman: " + (fox.hasNearbyHostile(32) ? "§cVAR!" : "§ayok"));
                yield true;
            }

            default -> true;
        };
    }

    // ── Yardımcı ──────────────────────────────────────────────────────────

    private void scanSurroundings() {
        // Önemli şeyleri bul ve raporla
        BlockPos ore = fox.findBlock("_ore", 16);
        if (ore != null) fox.broadcastToNearby("§e💎 Cevher buldum: " + ore);
        if (fox.hasNearbyHostile(12)) fox.broadcastToNearby("§c⚠ Yakında düşman var!");
        BlockPos village = fox.findBlock("cobblestone", 8);
        if (village != null) fox.broadcastToNearby("§a🏘️ Yapı kalıntısı ya da köy olabilir!");
    }

    private int getStepTimeout(StepType type) {
        return switch (type) {
            case WAIT_HARVEST -> 3000;
            case MINE_TUNNELS, MINE_DOWN -> 800;
            case COLLECT_WOOD, COLLECT_STONE, COLLECT_FOOD -> 600;
            case EXPLORE_NORTH, EXPLORE_EAST, EXPLORE_SOUTH, EXPLORE_WEST -> 400;
            case RETURN_HOME -> 500;
            case BUILD_WALLS, BUILD_FENCE, LIGHT_UP_AREA -> 300;
            default -> 200;
        };
    }

    private String taskName(TaskType type) {
        return switch (type) {
            case BUILD_BASE -> "Üs İnşaatı";
            case MINING_TRIP -> "Madencilik Seferi";
            case FARMING -> "Tarım Döngüsü";
            case GEAR_UP -> "Ekipman Hazırlığı";
            case STORAGE_SETUP -> "Depolama Kurulumu";
            case EXPLORE -> "Keşif Turu";
            case STOCK_RESOURCES -> "Kaynak Stoklama";
            case DEFEND_BASE -> "Savunma Sistemi";
        };
    }

    // ── Görev başlatma komutları (chat'ten çağrılır) ──────────────────────

    /** "!ai üs kur", "!ai madene git" gibi komutları parse et */
    public static TaskType parseCommand(String msg) {
        String m = msg.toLowerCase();
        if (m.contains("üs kur") || m.contains("ev yap") || m.contains("base kur") || m.contains("camp")) return TaskType.BUILD_BASE;
        if (m.contains("maden") || m.contains("elmas ara") || m.contains("mining")) return TaskType.MINING_TRIP;
        if (m.contains("tarım") || m.contains("çiftlik") || m.contains("hasat") || m.contains("farm")) return TaskType.FARMING;
        if (m.contains("ekipman") || m.contains("gear") || m.contains("silah yap") || m.contains("zırh")) return TaskType.GEAR_UP;
        if (m.contains("sandık") || m.contains("depo") || m.contains("storage")) return TaskType.STORAGE_SETUP;
        if (m.contains("keşif") || m.contains("explore") || m.contains("etrafı gez")) return TaskType.EXPLORE;
        if (m.contains("stok") || m.contains("kaynak topla") || m.contains("malzeme topla")) return TaskType.STOCK_RESOURCES;
        if (m.contains("savun") || m.contains("defend") || m.contains("çit çek")) return TaskType.DEFEND_BASE;
        return null;
    }
}

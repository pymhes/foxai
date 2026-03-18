package com.aimod.mod;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FoxAIEntity extends PathfinderMob implements MenuProvider {

    // ── Craft State Machine ────────────────────────────────────────────────
    // ── Görev Zinciri Sistemi ─────────────────────────────────────────────
    public final FoxAITaskSystem taskSystem = new FoxAITaskSystem(this);

    private enum CraftState { IDLE, GATHERING_WOOD, GOING_TO_TABLE, CRAFTING }
    private CraftState craftState = CraftState.IDLE;
    private String pendingToolType = null;
    private int craftWaitTicks = 0;

    // ── Kayıt ──────────────────────────────────────────────────────────────
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AiPlayerMod.MOD_ID);

    @SuppressWarnings("unchecked")
    public static final RegistryObject<EntityType<FoxAIEntity>> FOXAI =
        ENTITY_TYPES.register("foxai", () ->
            EntityType.Builder.<FoxAIEntity>of(FoxAIEntity::new, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(64)
                .build("foxai"));

    // ── Aksiyon Kuyruğu ───────────────────────────────────────────────────
    private final Queue<JsonObject> actionQueue = new ConcurrentLinkedQueue<>();
    private JsonObject currentAction = null;
    private int actionTimer = 0;
    private boolean isBusy = false;

    private Player followTarget = null;
    private Player guardTarget  = null;
    private UUID owner;

    // ── Envanter ──────────────────────────────────────────────────────────────
    public final SimpleContainer inventory = new SimpleContainer(FoxAIContainer.FOX_SLOTS);

    // ── Constructor ────────────────────────────────────────────────────────
    public FoxAIEntity(EntityType<? extends FoxAIEntity> type, Level level) {
        super(type, level);
        this.setCustomName(Component.literal("FoxAI [Bot]"));
        this.setCustomNameVisible(true);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(true);
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        nav.setCanPassDoors(true);
        nav.setCanFloat(true); // Suda yüzebilir
        return nav;
    }

    @Override
    public int getMaxFallDistance() {
        // Envanterinde su kovası varsa daha yüksekten düşebilir
        if (countItem("water_bucket") > 0) return 20;
        return 4; // Normal fall damage limiti
    }

    @Override
    protected float getStepHeight() {
        return 1.0f; // Vanilla 0.6f, biz 1 blok tırmanabiliriz
    }

    public void setOwner(Player player) {
        this.owner = player.getUUID();
    }

    public UUID getOwner() {
        return this.owner;
    }

    // ── Sağ Tık: Envanter Aç ──────────────────────────────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide() && hand == InteractionHand.MAIN_HAND) {
            player.openMenu(this);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    // MenuProvider implementasyonu — sadece GUI başlığı için
    @Override
    public Component getDisplayName() {
        return Component.literal("FoxAI Envanteri");
    }

    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInv, Player player) {
        return new FoxAIContainer(windowId, playerInv, this.inventory);
    }

    // ── NBT: Envanter kaydet/yükle ─────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ListTag invTag = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                stack.save(slotTag);
                invTag.add(slotTag);
            }
        }
        tag.put("FoxInventory", invTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("FoxInventory", Tag.TAG_LIST)) {
            ListTag invTag = tag.getList("FoxInventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < invTag.size(); i++) {
                CompoundTag slotTag = invTag.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < inventory.getContainerSize()) {
                    inventory.setItem(slot, ItemStack.of(slotTag));
                }
            }
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 40.0)
            .add(Attributes.MOVEMENT_SPEED, 0.35)
            .add(Attributes.ATTACK_DAMAGE, 6.0)
            .add(Attributes.FOLLOW_RANGE, 48.0)
            .add(Attributes.ARMOR, 4.0)
            .add(Attributes.ATTACK_SPEED, 1.5);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FoxAIActionGoal(this));
        this.goalSelector.addGoal(1, new FloatGoal(this));          // Suda yüz
        this.goalSelector.addGoal(2, new FoxAIFollowGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(4, new RandomStrollGoal(this, 0.8)); // Su dahil her yerde gezin
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true));
    }

    @Override
    public boolean canSwim() { return true; }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        // Fall damage hesapla ama su kovası veya merdiven varsa engelle
        if (onGround && this.fallDistance > 3) {
            if (this.inventory != null) {
                for (int i = 0; i < this.inventory.getContainerSize(); i++) {
                    var st = this.inventory.getItem(i);
                    if (!st.isEmpty() && st.getDescriptionId().contains("water_bucket")) {
                        // Su kovası var, MLG yap
                        this.fallDistance = 0;
                        BlockPos below = this.blockPosition().below();
                        if (this.level().isEmptyBlock(below))
                            this.level().setBlockAndUpdate(below, Blocks.WATER.defaultBlockState());
                        broadcastToNearby("💧 MLG su! Fall damage yok 😎");
                        return;
                    }
                }
            }
        }
        super.checkFallDamage(y, onGround, state, pos);
    }

    // ── Dışarıdan Çağrılan API ────────────────────────────────────────────

    /** Server tarafında çağrılır (ActionPacket.handle) */
    public void queueActions(String json) {
        actionQueue.clear();
        isBusy = true;
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                if (el.isJsonObject()) actionQueue.add(el.getAsJsonObject());
            }
            broadcastToNearby("§7▶ " + actionQueue.size() + " adım kuyruğa alındı!");
        } catch (Exception e) {
            AiPlayerMod.LOGGER.error("[FoxAI] JSON parse hatası: {}", e.getMessage());
        }
    }

    public void clearQueue() {
        actionQueue.clear();
        currentAction = null;
        isBusy = false;
        this.getNavigation().stop();
        this.setTarget(null);
        broadcastToNearby("§c🛑 Durdum.");
    }

    public boolean isBusy() { return isBusy; }

    // ── Tick ───────────────────────────────────────────────────────────────

    private int contextTickCounter = 0;

    // ── Hayatta Kalma State ────────────────────────────────────────────────
    private enum SurvivalState { IDLE, FLEEING, EATING, HEALING, BUILDING_SHELTER, SLEEPING, MORNING_ROUTINE }
    private SurvivalState survivalState = SurvivalState.IDLE;
    private int survivalTimer = 0;
    private boolean shelterBuilt = false;
    private BlockPos shelterPos = null;

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) return;

        // Guard sistemi
        if (guardTarget != null && guardTarget.isAlive() && !isBusy) {
            LivingEntity threat = findNearbyThreat();
            if (threat != null) this.setTarget(threat);
        }

        // Context paketi (her 2 saniye)
        if (++contextTickCounter >= 40) {
            contextTickCounter = 0;
            String ctx = buildWorldContext();
            FoxAINetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.NEAR.with(() ->
                    new net.minecraftforge.network.PacketDistributor.TargetPoint(
                        this.getX(), this.getY(), this.getZ(), 64, this.level().dimension())),
                new FoxAIContextPacket(ctx)
            );
        }

        // Görev zinciri sistemi
        if (taskSystem.isActive()) {
            taskSystem.tick();
        }

        // Hayatta kalma sistemi (meşgul değilken ve görev yokken çalışır)
        if (!isBusy && !taskSystem.isActive()) {
            tickSurvival();
        }
    }

    private LivingEntity findNearbyThreat() {
        if (guardTarget == null) return null;
        return this.level().getEntitiesOfClass(Monster.class,
            new AABB(guardTarget.blockPosition()).inflate(10))
            .stream().min(Comparator.comparingDouble(e -> e.distanceTo(this))).orElse(null);
    }

    // ── Aksiyon Goal ───────────────────────────────────────────────────────

    public static class FoxAIActionGoal extends Goal {
        private final FoxAIEntity fox;
        private int cooldown = 0;

        public FoxAIActionGoal(FoxAIEntity fox) {
            this.fox = fox;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
        }

        @Override public boolean canUse() { return !fox.actionQueue.isEmpty() || fox.currentAction != null; }
        @Override public boolean canContinueToUse() { return canUse(); }

        @Override
        public void tick() {
            if (cooldown > 0) { cooldown--; return; }

            if (fox.currentAction == null) {
                fox.currentAction = fox.actionQueue.poll();
                if (fox.currentAction == null) { fox.isBusy = false; return; }
                fox.actionTimer = 0;
            }

            boolean done = fox.processAction(fox.currentAction);
            fox.actionTimer++;

            int maxTicks = getMaxTicks(fox.currentAction);
            if (done || fox.actionTimer > maxTicks) {
                fox.currentAction = null;
                cooldown = 4;
            }
        }

        private int getMaxTicks(JsonObject a) {
            return switch (str(a, "type")) {
                // mine: craft + ağaç topla + crafting table'a git + kaz = 600 tick (30sn)
                case "mine"   -> 600;
                case "move", "sprint" -> 100;
                case "attack" -> 80;
                case "follow", "guard" -> 200;
                case "wait"   -> 60;
                case "eat"    -> 50;
                case "craft", "smelt" -> 200;
                default -> 40;
            };
        }
    }

    // ── Ana Aksiyon İşleyici ───────────────────────────────────────────────

    public boolean processAction(JsonObject a) {
        String type    = str(a, "type");
        String target  = str(a, "target");
        String dir     = str(a, "direction");
        String extra   = str(a, "extra");
        String message = str(a, "message");
        int qty    = integer(a, "quantity", 1);
        int steps  = integer(a, "steps", 5);
        int radius = integer(a, "radius", 6);

        return switch (type) {
            case "think"     -> { broadcastToNearby("§b💭 " + coal(message, extra, "...")); yield true; }
            case "warn"      -> { broadcastToNearby("§c⚠ " + coal(message, extra, "Dikkat!")); yield true; }
            case "say"       -> { broadcastToNearby("§f" + coal(message, target, extra)); yield true; }
            case "whisper"   -> { whisperTo(target, message); yield true; }
            case "celebrate" -> {
                this.jumpFromGround();
                broadcastToNearby("§e🎉 " + coal(extra, "gg ez win!"));
                yield true;
            }
            case "emote"     -> {
                String em = coal(extra, target, "wave");
                if ("dance".equals(em)) { this.jumpFromGround(); broadcastToNearby("§d💃 dans ediyorum!"); }
                else if ("bow".equals(em)) broadcastToNearby("§7🙇 saygılarımla knk");
                else broadcastToNearby("§a👋 selamlar! Ben FoxAI 🦊");
                yield true;
            }
            case "stop", "stop_follow" -> {
                clearQueue();
                if (taskSystem.isActive()) taskSystem.cancel();
                yield true;
            }
            // Görev zinciri başlatma
            case "task" -> {
                String taskMsg = coal(target, message, extra);
                if (taskMsg != null) {
                    FoxAITaskSystem.TaskType tt = FoxAITaskSystem.parseCommand(taskMsg);
                    if (tt != null) {
                        taskSystem.start(tt);
                    } else {
                        broadcastToNearby("§c❓ Görev anlaşılamadı: " + taskMsg);
                    }
                }
                yield true;
            }

            // ── Hareket ──────────────────────────────────────────────────
            case "move"   -> executeMove(dir, steps, target, 1.0);
            case "sprint" -> { this.setSprinting(true); yield executeMove(dir, steps, target, 1.4); }
            case "jump"   -> { for (int i=0; i<Math.min(qty,3); i++) this.jumpFromGround(); yield true; }
            case "sneak"  -> true;
            case "look"   -> { executeLook(dir, target); yield true; }

            // ── Blok ──────────────────────────────────────────────────────
            case "mine"   -> executeMine(target, qty, radius);
            case "place"  -> { broadcastToNearby("🧱 koyuyorum: §e" + coal(target,"blok")); yield true; }

            // ── Fall Damage ───────────────────────────────────────────────
            case "safe_fall"    -> executeSafeFall(extra);
            case "place_water"  -> executePlaceWater(dir);
            case "place_ladder" -> executePlaceLadder(dir, qty);

            // ── Savaş ─────────────────────────────────────────────────────
            case "attack" -> executeAttack(target, qty);
            case "equip"  -> { executeEquip(target); yield true; }
            case "shield" -> true;

            // ── Sosyal ────────────────────────────────────────────────────
            case "follow", "guard" -> {
                Player p = findPlayer(target);
                if (p != null) {
                    followTarget = p;
                    if ("guard".equals(type)) guardTarget = p;
                    broadcastToNearby("👣 " + p.getName().getString() + (
                        "guard".equals(type) ? "'i koruyorum!" : "'i takip ediyorum!"));
                }
                yield true;
            }

            // ── Craft/Yemek/Diğer ─────────────────────────────────────────
            case "craft"  -> { broadcastToNearby("⚒️ craft: §e" + coal(target,"?") + " §7x"+qty); yield true; }
            case "smelt"  -> { broadcastToNearby("🔥 eritiyorum: §e" + coal(target,"?") + " §7x"+qty); yield true; }
            case "eat"    -> { broadcastToNearby("🍖 yiyorum, afiyet olsun!"); yield true; }
            case "fish"   -> { broadcastToNearby("🎣 olta attım!"); yield true; }
            case "farm"   -> { ensureTool("shovel"); broadcastToNearby("🌾 çiftlik işliyorum..."); yield true; }
            case "sleep"  -> { broadcastToNearby("💤 yatak arıyorum..."); yield true; }
            case "sort_inventory" -> { broadcastToNearby("🎒 envanter düzenlendi!"); yield true; }
            case "wait"   -> actionTimer >= 40;
            case "open_chest", "close_chest", "use_item", "interact" -> {
                broadcastToNearby("🤝 " + type.replace("_"," ") + "...");
                yield true;
            }
            default -> true;
        };
    }

    // ── Hareket ───────────────────────────────────────────────────────────

    private BlockPos moveTarget = null;
    private int stuckCounter = 0;
    private Vec3 lastPos = null;

    private boolean executeMove(String dir, int steps, String targetName, double speed) {
        if (actionTimer == 0) {
            moveTarget = resolveDestination(dir, steps, targetName);
            stuckCounter = 0;
            lastPos = this.position();
            if (moveTarget != null) {
                // Navigation ayarları — tırmanma ve yüzme aktif
                if (this.getNavigation() instanceof GroundPathNavigation nav) {
                    nav.setCanOpenDoors(true);
                    nav.setCanPassDoors(true);
                }
                this.getNavigation().moveTo(moveTarget.getX()+0.5, moveTarget.getY(), moveTarget.getZ()+0.5, speed);
                broadcastToNearby("🚶 " + dirTR(dir) + " gidiyorum...");
            }
        }

        if (moveTarget == null) return true;

        // Takılma tespiti: her 10 tick'te pozisyon kontrolü
        if (actionTimer % 10 == 0) {
            Vec3 curPos = this.position();
            if (lastPos != null && curPos.distanceTo(lastPos) < 0.3) {
                stuckCounter++;
                if (stuckCounter >= 2) {
                    // Takıldı — zıpla ve farklı path dene
                    this.jumpFromGround();
                    stuckCounter = 0;
                    // Path'i yenile
                    this.getNavigation().moveTo(moveTarget.getX()+0.5, moveTarget.getY(), moveTarget.getZ()+0.5, speed);
                }
            } else {
                stuckCounter = 0;
            }
            lastPos = curPos;
        }

        // Suda mıyız? Zıplayarak yüz
        if (this.isInWater()) {
            this.jumpFromGround();
        }

        // Hedefe yeterince yaklaştık mı?
        double distToTarget = this.blockPosition().distSqr(moveTarget);
        if (distToTarget < 4.0) return true; // 2 blok içinde

        return !this.getNavigation().isInProgress() && actionTimer > 10;
    }

    private BlockPos resolveDestination(String dir, int steps, String targetName) {
        if (targetName != null && !targetName.isEmpty()) {
            Player p = findPlayer(targetName);
            if (p != null) return p.blockPosition();
        }
        BlockPos pos = this.blockPosition();
        if (dir == null) return pos.relative(this.getDirection(), steps);
        return switch (dir.toLowerCase()) {
            case "north","kuzey","forward","ileri" -> pos.north(steps);
            case "south","güney","back","geri"     -> pos.south(steps);
            case "east","doğu","right","sağ"       -> pos.east(steps);
            case "west","batı","left","sol"        -> pos.west(steps);
            case "up","yukarı"   -> pos.above(steps);
            case "down","aşağı"  -> pos.below(steps);
            default -> pos.relative(this.getDirection(), steps);
        };
    }

    private void executeLook(String dir, String targetName) {
        if (targetName != null && !targetName.isEmpty()) {
            Player p = findPlayer(targetName);
            if (p != null) { this.getLookControl().setLookAt(p); return; }
        }
        if (dir == null) return;
        float yaw = switch (dir.toLowerCase()) {
            case "north","kuzey" -> 180f; case "south","güney" -> 0f;
            case "east","doğu"   -> -90f; case "west","batı"   -> 90f;
            default -> this.getYRot();
        };
        this.setYRot(yaw);
    }

    // ── Kuşanma ───────────────────────────────────────────────────────────

    // Alet güç sıralaması: yüksek = daha iyi
    private static int toolTier(String descId) {
        if (descId.contains("netherite")) return 5;
        if (descId.contains("diamond"))   return 4;
        if (descId.contains("iron"))      return 3;
        if (descId.contains("stone") || descId.contains("golden")) return 2;
        if (descId.contains("wooden") || descId.contains("wood"))  return 1;
        return 0;
    }

    /** En iyi baltayı/kazımayı/kılıcı ele al. toolType: "axe", "pickaxe", "sword", "shovel" */
    public boolean equipBestTool(String toolType) {
        ItemStack best = ItemStack.EMPTY;
        int bestTier = -1;
        for (var slot : this.getHandSlots()) {
            if (slot.isEmpty()) continue;
            String id = slot.getDescriptionId().toLowerCase();
            if (id.contains(toolType) && toolTier(id) > bestTier) {
                best = slot; bestTier = toolTier(id);
            }
        }
        // Bulunan aleti ana ele taşı
        if (!best.isEmpty()) {
            this.setItemInHand(InteractionHand.MAIN_HAND, best);
            return true;
        }
        return false; // uygun alet yok
    }

    private void executeEquip(String item) {
        if (item == null || item.isEmpty()) { broadcastToNearby("🎒 ne kuşanayım?"); return; }
        String toolType = switch (item.toLowerCase()) {
            case "best_weapon", "sword", "kılıç" -> "sword";
            case "best_pickaxe", "pickaxe", "kazma" -> "pickaxe";
            case "best_axe", "axe", "balta" -> "axe";
            case "elmas balta", "diamond_axe" -> "axe";
            case "elmas kılıç", "diamond_sword" -> "sword";
            case "elmas kazma", "diamond_pickaxe" -> "pickaxe";
            case "taş balta", "stone_axe" -> "axe";
            default -> item.toLowerCase().replace(" ", "_");
        };
        if (equipBestTool(toolType)) {
            broadcastToNearby("⚔️ kuşandım: §e" + this.getMainHandItem().getDisplayName().getString() + " §7✓");
        } else {
            broadcastToNearby("🎒 §e" + item + " §7elimde yok!");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── ALET SİSTEMİ — ensureTool() merkezi metot ─────────────────────────
    // Her aksiyon başında çağrılır: kazma, balta, kürek, kılıç
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Bloğa göre doğru alet tipini döndürür.
     * executeMine, executeAttack, farm gibi her aksiyon bunu çağırır.
     */
    private String getToolTypeForBlock(String blockName) {
        if (blockName == null) return "axe";
        String b = blockName.toLowerCase();
        if (b.contains("taş") || b.contains("stone") || b.contains("ore") ||
            b.contains("cevher") || b.contains("cobble") || b.contains("obsidyen") ||
            b.contains("obsidian") || b.contains("elmas") || b.contains("demir") ||
            b.contains("altın") || b.contains("kömür") || b.contains("netherrack") ||
            b.contains("sandstone") || b.contains("kum taşı")) return "pickaxe";
        if (b.contains("toprak") || b.contains("dirt") || b.contains("kum") ||
            b.contains("sand") || b.contains("çakıl") || b.contains("gravel") ||
            b.contains("soul_sand") || b.contains("podzol")) return "shovel";
        // tahta, ağaç, log, odun, ahşap, çoğu blok → balta
        return "axe";
    }

    /**
     * Merkezi alet kontrol + otomatik craft sistemi.
     * @return true  → alet hazır, aksiyona devam et
     *         false → henüz hazır değil, bu tick'te bekle
     */
    public boolean ensureTool(String toolType) {
        // Craft zaten devam ediyorsa state machine'i çalıştır
        if (craftState != CraftState.IDLE) {
            boolean done = runCraftStateMachine(pendingToolType != null ? pendingToolType : toolType);
            if (done) {
                craftState = CraftState.IDLE;
                pendingToolType = null;
                // Craft bitti, şimdi tekrar alet kontrolü yap
                if (equipBestTool(toolType)) return true;
                // Alet hâlâ yoksa (craft başarısız) elle devam et
                return true;
            }
            return false;
        }

        // İyi bir alet elimizde mi? (sadece actionTimer == 0 yani ilk tick'te karar ver)
        if (actionTimer > 0) {
            // Sonraki tick'lerde sadece elimdekini kullan, yeniden craft başlatma
            return true;
        }

        if (equipBestTool(toolType)) {
            int tier = toolTier(this.getMainHandItem().getDescriptionId().toLowerCase());
            if (tier >= 2) return true; // taş ve üzeri → yeterli
            if (tier >= 1) {
                // Tahta alet var ama zayıf — daha iyisini yapmayı dene
                broadcastToNearby("🔧 §e" + this.getMainHandItem().getDisplayName().getString()
                    + " §7var ama zayıf, daha iyisini yapıyorum...");
            }
        } else {
            broadcastToNearby("🔧 §e" + toolType + " §7yok! Otomatik craft başlatıyorum...");
        }

        // Craft başlat (sadece bir kez — actionTimer == 0 garantisi var)
        craftState = CraftState.GATHERING_WOOD;
        pendingToolType = toolType;
        craftWaitTicks = 0;
        return false;
    }

    // ── Kazma ─────────────────────────────────────────────────────────────

    private boolean executeMine(String blockName, int qty, int radius) {
        // Her seferinde doğru aleti seç/yap
        String toolType = getToolTypeForBlock(blockName);
        if (!ensureTool(toolType)) return false; // alet hazır değil

        if (actionTimer == 0)
            broadcastToNearby("⛏️ §e" + coal(blockName,"blok") + " §7x" + qty + " kırıyorum!");

        BlockPos found = findBlock(blockName, radius);
        if (found == null) {
            if (actionTimer > 60) { broadcastToNearby("⛏️ §e" + coal(blockName,"blok") + " §7bulunamadı."); return true; }
            return false;
        }

        double dist = this.distanceToSqr(Vec3.atCenterOf(found));
        if (dist > 6.0) { // 2.5 blok mesafe yeterli
            // Hedefe giderken takılıyor mu?
            if (actionTimer % 15 == 0 && this.getNavigation().isInProgress()) {
                // Path yenile
                this.getNavigation().moveTo(found.getX()+0.5, found.getY(), found.getZ()+0.5, 1.2);
            } else if (!this.getNavigation().isInProgress()) {
                this.jumpFromGround(); // Belki bir blok önünde duvar var
                this.getNavigation().moveTo(found.getX()+0.5, found.getY(), found.getZ()+0.5, 1.2);
            }
            return false;
        }

        // Bloğa bak
        Vec3 blockCenter = Vec3.atCenterOf(found);
        this.getLookControl().setLookAt(blockCenter.x, blockCenter.y, blockCenter.z, 30, 30);

        if (!this.level().isClientSide()) {
            String bName = this.level().getBlockState(found).getBlock().getName().getString();
            this.level().destroyBlock(found, true, this);
            pickupNearbyItems();
            broadcastToNearby("⛏️ §e" + bName + " §7kırıldı! " + (qty > 1 ? (qty-1) + " kaldı" : ""));
        }
        return qty <= 1;
    }

    // ── Craft State Machine ────────────────────────────────────────────────

    /**
     * Adım adım alet yapım süreci.
     * @return true = tamamlandı, false = devam ediyor
     */
    private boolean runCraftStateMachine(String toolType) {
        switch (craftState) {

            // ADIM 1: Ağaç/malzeme topla
            case GATHERING_WOOD -> {
                // Önce yerdeki itemleri topla
                pickupNearbyItems();

                if (hasCraftMaterials(toolType)) {
                    broadcastToNearby("📦 malzeme tamam, crafting table'a gidiyorum!");
                    craftState = CraftState.GOING_TO_TABLE;
                    return false;
                }

                BlockPos tree = findBlock("log", 20);
                if (tree == null) {
                    broadcastToNearby("§cMalzeme ve ağaç bulunamadı! Elle kırıyorum 😅");
                    this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    return true;
                }

                double dist = this.distanceToSqr(Vec3.atCenterOf(tree));
                if (dist > 9.0) {
                    if (!this.getNavigation().isInProgress()) {
                        broadcastToNearby("🌲 ağaca gidiyorum...");
                        this.getNavigation().moveTo(tree.getX()+0.5, tree.getY(), tree.getZ()+0.5, 1.2);
                    }
                    return false;
                }

                if (!this.level().isClientSide()) {
                    this.level().destroyBlock(tree, true, this);
                    broadcastToNearby("🪵 kestim, topluyorum...");
                }
                craftWaitTicks++;
                if (craftWaitTicks < 5) return false;
                pickupNearbyItems();
                craftWaitTicks = 0;
                broadcastToNearby("🪵 topladım! (" + countItem("log") + " log)");
                if (hasCraftMaterials(toolType)) craftState = CraftState.GOING_TO_TABLE;
                return false;
            }

            // ADIM 2: Crafting table'a git (yoksa yap)
            case GOING_TO_TABLE -> {
                BlockPos table = findBlock("crafting_table", 32);

                if (table == null) {
                    // Crafting table yok — kendimiz yapalım
                    broadcastToNearby("🪓 crafting table yok, yapıyorum...");
                    if (countItem("log") >= 1 && !this.level().isClientSide()) {
                        BlockPos front = this.blockPosition().relative(this.getDirection());
                        if (this.level().isEmptyBlock(front)) {
                            this.level().setBlockAndUpdate(front, Blocks.CRAFTING_TABLE.defaultBlockState());
                            table = front;
                            broadcastToNearby("✅ crafting table yerleştirdim!");
                        }
                    }
                    if (table == null) {
                        broadcastToNearby("§cCrafting table yapamadım, sihirle çözüyorum 🪄");
                        craftState = CraftState.CRAFTING;
                        return false;
                    }
                }

                double dist = this.distanceToSqr(Vec3.atCenterOf(table));
                if (dist > 6.0) {
                    if (!this.getNavigation().isInProgress()) {
                        this.getNavigation().moveTo(table.getX()+0.5, table.getY(), table.getZ()+0.5, 1.1);
                        broadcastToNearby("🚶 crafting table'a gidiyorum...");
                    }
                    return false;
                }
                broadcastToNearby("🔨 crafting table'a ulaştım, yapıyorum...");
                craftState = CraftState.CRAFTING;
                craftWaitTicks = 0;
                return false;
            }

            // ADIM 3: Gerçek craft dene, olmadı → sihirli craft
            case CRAFTING -> {
                if (++craftWaitTicks < 8) return false;

                // Önce gerçek craft
                if (tryCraftTool(toolType)) {
                    equipBestTool(toolType);
                    broadcastToNearby("✅ §e" + this.getMainHandItem().getDisplayName().getString()
                        + " §7yaptım! Let's go kanka 🎉");
                    return true;
                }

                // tryCraftTool başarısız → son çare sihirli craft
                // NOT: Bu noktaya sadece malzeme gerçekten yoksa gelinir
                broadcastToNearby("⚠ malzeme yetersiz, sihirle yapıyorum 🪄");
                net.minecraft.world.item.Item magic = getMagicCraftItem(toolType);
                if (magic != null && !this.level().isClientSide()) {
                    // Sihirli craftta da mevcut malzemeleri tüket (ne kadar varsa)
                    String mat = getBestMaterial();
                    int neededMat = switch (toolType) { case "sword" -> 2; case "shovel" -> 1; default -> 3; };
                    int neededStick = "sword".equals(toolType) ? 1 : 2;
                    // Stick yoksa üret
                    if (countItem("stick") < neededStick && countItem("planks") >= 2) {
                        consumeItem("planks", 2);
                        giveItem(new net.minecraft.world.item.ItemStack(Items.STICK, 4));
                    } else if (countItem("stick") < neededStick && countItem("log") >= 1) {
                        consumeItem("log", 1);
                        giveItem(new net.minecraft.world.item.ItemStack(Items.OAK_PLANKS, 4));
                        consumeItem("planks", 2);
                        giveItem(new net.minecraft.world.item.ItemStack(Items.STICK, 4));
                    }
                    // Elindeki kadar malzemeyi tüket
                    consumeItem(mat, Math.min(neededMat, countItem(mat)));
                    consumeItem("stick", Math.min(neededStick, countItem("stick")));
                    giveItem(new net.minecraft.world.item.ItemStack(magic, 1));
                    equipBestTool(toolType);
                    broadcastToNearby("✅ §e" + this.getMainHandItem().getDisplayName().getString() + " §7yaptım!");
                }
                return true;
            }

            default -> { return true; }
        }
    }

    /** Alet yapmak için yeterli malzeme var mı? */
    private boolean hasCraftMaterials(String toolType) {
        int needed = 2; // 2 yeterli (sword için de, diğerleri için de)
        return countItem("diamond") >= needed ||
               countItem("iron_ingot") >= needed ||
               countItem("cobblestone") >= needed ||
               countItem("stone") >= needed ||
               countItem("planks") >= needed ||
               countItem("log") >= 1; // 1 log → 4 planks → yeterli
    }

    /**
     * Gerçek craft: Minecraft tariflerine uygun malzeme tüketimi + aleti ver.
     *
     * Tarifler:
     *   axe/pickaxe  → 3 malzeme + 2 stick
     *   sword        → 2 malzeme + 1 stick
     *   shovel       → 1 malzeme + 2 stick
     *
     * Malzeme önceliği: elmas > demir > taş/çakıl > tahta
     * Stick yoksa: log → planks → stick zinciri
     */
    private boolean tryCraftTool(String toolType) {
        if (this.level().isClientSide()) return false;
        try {
            net.minecraft.world.item.Item result = getMagicCraftItem(toolType);
            if (result == null) return false;

            String material = getBestMaterial();

            // Her alet tipi için gereken miktarlar (Minecraft tarifleri)
            int neededMat   = switch (toolType) {
                case "sword"  -> 2;
                case "shovel" -> 1;
                default       -> 3; // axe, pickaxe
            };
            int neededStick = switch (toolType) {
                case "sword" -> 1;
                default      -> 2; // axe, pickaxe, shovel
            };

            // Stick yoksa üret: log → planks → stick
            if (countItem("stick") < neededStick) {
                // Planks yoksa logdan üret
                if (countItem("planks") < 2 && countItem("log") >= 1) {
                    consumeItem("log", 1);
                    giveItem(new net.minecraft.world.item.ItemStack(Items.OAK_PLANKS, 4));
                    broadcastToNearby("🪵 log → 4 tahta yaptım");
                }
                // Stickları üret
                if (countItem("planks") >= 2) {
                    consumeItem("planks", 2);
                    giveItem(new net.minecraft.world.item.ItemStack(Items.STICK, 4));
                    broadcastToNearby("🥢 tahta → çubuk yaptım");
                }
            }

            // Malzeme ve stick yeterli mi?
            if (countItem("stick") < neededStick || countItem(material) < neededMat) {
                broadcastToNearby("§cMalzeme yetmedi: " + neededMat + "x " + material
                    + " + " + neededStick + "x stick lazım");
                return false;
            }

            // Malzemeleri tüket ve aleti ver
            consumeItem(material, neededMat);
            consumeItem("stick", neededStick);
            giveItem(new net.minecraft.world.item.ItemStack(result, 1));
            broadcastToNearby("🔨 " + neededMat + "x §e" + material
                + " §7+ " + neededStick + "x stick → §e"
                + result.getDescriptionId().replace("item.minecraft.", "") + " §7✅");
            return true;
        } catch (Exception e) {
            broadcastToNearby("§cCraft hatası: " + e.getMessage());
            return false;
        }
    }

    /** En iyi mevcut malzemeyi döndür */
    private String getBestMaterial() {
        if (countItem("diamond") >= 2)    return "diamond";
        if (countItem("iron_ingot") >= 2) return "iron_ingot";
        // cobblestone veya stone — ikisi de taş alet yapar
        if (countItem("cobblestone") >= 2 || countItem("stone") >= 2) return "cobblestone";
        return "planks";
    }

    /** toolType + mevcut malzemeye göre üretilecek item */
    private net.minecraft.world.item.Item getMagicCraftItem(String toolType) {
        String mat = getBestMaterial();
        return switch (toolType) {
            case "pickaxe" -> switch (mat) {
                case "diamond"    -> Items.DIAMOND_PICKAXE;
                case "iron_ingot" -> Items.IRON_PICKAXE;
                default           -> Items.STONE_PICKAXE;
            };
            case "axe" -> switch (mat) {
                case "diamond"    -> Items.DIAMOND_AXE;
                case "iron_ingot" -> Items.IRON_AXE;
                default           -> Items.STONE_AXE;
            };
            case "shovel" -> switch (mat) {
                case "diamond"    -> Items.DIAMOND_SHOVEL;
                case "iron_ingot" -> Items.IRON_SHOVEL;
                default           -> Items.STONE_SHOVEL;
            };
            case "sword" -> switch (mat) {
                case "diamond"    -> Items.DIAMOND_SWORD;
                case "iron_ingot" -> Items.IRON_SWORD;
                default           -> Items.STONE_SWORD;
            };
            default -> null;
        };
    }

    /** Envanterdeki belirli item sayısını say (el + zırh slotları) */
    /** Yakındaki düşmüş itemleri FoxAI'nin eline al */
    public void pickupNearbyItems() {
        if (this.level().isClientSide()) return;
        var items = this.level().getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity.class,
            new AABB(this.blockPosition()).inflate(3)
        );
        for (var item : items) {
            if (item.isAlive() && !item.getItem().isEmpty()) {
                ItemStack stack = item.getItem().copy();
                // Main hand boşsa al, değilse off-hand'e
                if (this.getMainHandItem().isEmpty()) {
                    this.setItemInHand(InteractionHand.MAIN_HAND, stack);
                } else if (this.getOffhandItem().isEmpty()) {
                    this.setItemInHand(InteractionHand.OFF_HAND, stack);
                }
                item.discard(); // yerdeki entity'yi kaldır
            }
        }
    }

    public int countItem(String partialId) {
        int count = 0;
        // El slotları
        for (var slot : this.getHandSlots())
            if (!slot.isEmpty() && slot.getDescriptionId().toLowerCase().contains(partialId))
                count += slot.getCount();
        // Zırh slotları
        for (var slot : this.getArmorSlots())
            if (!slot.isEmpty() && slot.getDescriptionId().toLowerCase().contains(partialId))
                count += slot.getCount();
        // Ana envanter (SimpleContainer)
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var st = inventory.getItem(i);
            if (!st.isEmpty() && st.getDescriptionId().toLowerCase().contains(partialId))
                count += st.getCount();
        }
        return count;
    }

    /** Envanterdeki belirli item'ı tüket */
    public void consumeItem(String partialId, int amount) {
        if (this.level().isClientSide()) return;
        int remaining = amount;
        // Önce el slotlarından tüket
        for (var slot : this.getHandSlots()) {
            if (remaining <= 0) break;
            if (!slot.isEmpty() && slot.getDescriptionId().toLowerCase().contains(partialId)) {
                int take = Math.min(remaining, slot.getCount());
                slot.shrink(take);
                remaining -= take;
            }
        }
        // Sonra ana envanterden
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            var st = inventory.getItem(i);
            if (!st.isEmpty() && st.getDescriptionId().toLowerCase().contains(partialId)) {
                int take = Math.min(remaining, st.getCount());
                st.shrink(take);
                inventory.setItem(i, st.isEmpty() ? ItemStack.EMPTY : st);
                remaining -= take;
            }
        }
    }

    /** FoxAI'ye item ver — önce ana envantere, doluysa ele, olmadı yere at */
    public void giveItem(net.minecraft.world.item.ItemStack stack) {
        if (this.level().isClientSide()) return;
        // Ana envanterden boş slot bul
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack);
                return;
            }
        }
        // Envanter doluysa ele al
        if (this.getMainHandItem().isEmpty()) {
            this.setItemInHand(InteractionHand.MAIN_HAND, stack);
        } else {
            this.spawnAtLocation(stack);
        }
    }

    public BlockPos findBlock(String name, int radius) {
        BlockPos origin = this.blockPosition();
        String search = name == null ? "" : name.toLowerCase()
            .replace("tahta","log").replace("ahşap","log")
            .replace("ağaç","log").replace("odun","log")
            .replace("taş","stone").replace("çakıl taşı","cobblestone")
            .replace("elmas cevheri","diamond_ore").replace("elmas","diamond_ore")
            .replace("demir cevheri","iron_ore").replace("demir","iron_ore")
            .replace("altın","gold_ore").replace("kömür","coal_ore")
            .replace("toprak","dirt").replace("kum","sand")
            .replace("çakıl","gravel").replace("obsidyen","obsidian")
            .replace(" ","_");

        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -4; dy <= 4; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState bs = this.level().getBlockState(p);
                    if (bs.isAir() || bs.getBlock() == Blocks.BEDROCK) continue;
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(bs.getBlock());
                    if (id == null) continue;
                    String path = id.getPath();
                    if (search.isEmpty() || path.contains(search)) return p;
                }
        return null;
    }

    // ── Fall Damage Koruması ───────────────────────────────────────────────

    private boolean executeSafeFall(String method) {
        boolean hasWater  = hasItemInInventory("water_bucket");
        boolean hasLadder = hasItemInInventory("ladder");
        if ("water".equalsIgnoreCase(method) || hasWater) return executePlaceWater("down");
        if (hasLadder) return executePlaceLadder("down", 3);
        broadcastToNearby("§cne su kovası var ne merdiven! 😬 shift ile yavaş iniyorum...");
        return true;
    }

    private boolean executePlaceWater(String dir) {
        if (hasItemInInventory("water_bucket")) {
            broadcastToNearby("🪣 MLG su attım! Fall damage yok 💧");
            BlockPos below = this.blockPosition().below();
            if (!this.level().isClientSide() && this.level().isEmptyBlock(below)) {
                this.level().setBlockAndUpdate(below, Blocks.WATER.defaultBlockState());
            }
        } else {
            broadcastToNearby("§csu kovası yok, merdiven deniyorum...");
            executePlaceLadder(dir, 3);
        }
        return true;
    }

    private boolean executePlaceLadder(String dir, int qty) {
        if (hasItemInInventory("ladder")) {
            broadcastToNearby("🪜 merdiven koyuyorum x" + qty + " — güvenli iniş!");
        } else {
            broadcastToNearby("§cmerdiven de yok bro 😅 dikkatli in kanka");
        }
        return true;
    }

    // ── Saldırı ────────────────────────────────────────────────────────────

    private boolean executeAttack(String targetName, int qty) {
        // Önce kılıç kontrolü
        if (!ensureTool("sword")) return false;

        if (actionTimer == 0) broadcastToNearby("⚔️ §e" + coal(targetName,"düşman") + "§7a saldırıyorum!");

        LivingEntity enemy = findEnemy(targetName);
        if (enemy == null) {
            if (actionTimer > 20) { broadcastToNearby("⚔️ düşman bulunamadı."); return true; }
            return false;
        }

        if (this.distanceTo(enemy) > 3.5) {
            this.getNavigation().moveTo(enemy, 1.3);
            return false;
        }

        this.setTarget(enemy);
        this.swing(InteractionHand.MAIN_HAND);
        this.doHurtTarget(enemy);

        if (!enemy.isAlive()) {
            broadcastToNearby("⚔️ §e" + enemy.getName().getString() + " §7temizlendi! gg ez");
            return true;
        }
        return qty <= 1 && actionTimer > 20;
    }

    private LivingEntity findEnemy(String name) {
        List<LivingEntity> list = this.level().getEntitiesOfClass(LivingEntity.class,
            new AABB(this.blockPosition()).inflate(12),
            e -> e != this && !(e instanceof Player) && e.isAlive());

        if (name != null && !name.equals("nearest") && !name.equals("all"))
            list = list.stream().filter(e ->
                e.getType().getDescriptionId().toLowerCase().contains(name.toLowerCase()) ||
                e.getName().getString().toLowerCase().contains(name.toLowerCase())).toList();

        return list.stream().min(Comparator.comparingDouble(e -> e.distanceTo(this))).orElse(null);
    }

    // ── Follow Goal ────────────────────────────────────────────────────────

    public static class FoxAIFollowGoal extends Goal {
        private final FoxAIEntity fox;
        private int tick = 0;

        public FoxAIFollowGoal(FoxAIEntity fox) {
            this.fox = fox;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() { return fox.followTarget != null && fox.followTarget.isAlive() && !fox.isBusy; }

        @Override
        public void tick() {
            if (++tick < 10) return;
            tick = 0;
            Player p = fox.followTarget;
            if (p == null || !p.isAlive()) { fox.followTarget = null; return; }
            if (fox.distanceTo(p) > 5) fox.getNavigation().moveTo(p, 1.1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── HAYATTa KALMA SİSTEMİ ─────────────────────────────────────────────
    // isBusy=false olduğunda her tick çalışır. Tehlike, açlık, gece algılar.
    // ══════════════════════════════════════════════════════════════════════

    private void tickSurvival() {
        survivalTimer++;

        // Öncelik sırası: Tehlike > Yaralanma > Açlık > Gece > Sabah Rutini
        switch (survivalState) {
            case IDLE -> checkSurvivalConditions();
            case FLEEING -> tickFleeing();
            case HEALING -> tickHealing();
            case EATING -> tickEating();
            case BUILDING_SHELTER -> tickBuildingShelter();
            case SLEEPING -> tickSleeping();
            case MORNING_ROUTINE -> tickMorningRoutine();
        }
    }

    /** Her tick tehlikeleri kontrol et */
    private void checkSurvivalConditions() {
        // Sadece her 20 tick'te kontrol et (performans)
        if (survivalTimer % 20 != 0) return;

        float health = this.getHealth();
        float maxHealth = this.getMaxHealth();
        boolean isNight = this.level().isNight();

        // 1. TEHLİKE: Can %30 altı + yakında düşman → KAÇ
        if (health < maxHealth * 0.3f && hasNearbyHostile(8)) {
            broadcastToNearby("§c💀 CAN AZ, KAÇIYORUM! (" + (int)health + "/" + (int)maxHealth + ")");
            survivalState = SurvivalState.FLEEING;
            survivalTimer = 0;
            return;
        }

        // 2. İYİLEŞME: Can %60 altı + düşman yok → İyileş
        if (health < maxHealth * 0.6f && !hasNearbyHostile(12)) {
            survivalState = SurvivalState.HEALING;
            survivalTimer = 0;
            return;
        }

        // 3. AÇLIK: Envanterde yemek var mı kontrol
        if (hasFood() && survivalTimer % 100 == 0) {
            // Arada sırada yemek ye (simülasyon)
            survivalState = SurvivalState.EATING;
            survivalTimer = 0;
            return;
        }

        // 4. GECE: Gece oldu ve açıkta mı?
        if (isNight && !isInsideShelter() && !shelterBuilt) {
            broadcastToNearby("§6🌙 Gece oluyor, sığınak arıyorum...");
            survivalState = SurvivalState.BUILDING_SHELTER;
            survivalTimer = 0;
            return;
        }

        // 5. UYKU: Gece, sığınakta, yatak var
        if (isNight && isInsideShelter() && findBlock("bed", 8) != null) {
            survivalState = SurvivalState.SLEEPING;
            survivalTimer = 0;
            return;
        }

        // 6. SABAH RUTİNİ: Gündüz oldu, dışarı çık
        if (!isNight && survivalState == SurvivalState.SLEEPING) {
            survivalState = SurvivalState.MORNING_ROUTINE;
            survivalTimer = 0;
        }
    }

    // ── TEHLİKEDEN KAÇMA ─────────────────────────────────────────────────

    private void tickFleeing() {
        LivingEntity nearest = findNearestHostile(16);

        if (nearest == null || survivalTimer > 100) {
            // Tehlike geçti veya timeout
            broadcastToNearby("§a😮‍💨 Tehlike geçti, duruyorum.");
            survivalState = SurvivalState.IDLE;
            return;
        }

        // Düşmandan ters yönde kaç
        Vec3 fleeDir = this.position().subtract(nearest.position()).normalize();
        Vec3 fleeTarget = this.position().add(fleeDir.scale(16));
        BlockPos fleeTo = new BlockPos((int)fleeTarget.x, (int)this.getY(), (int)fleeTarget.z);

        if (!this.getNavigation().isInProgress()) {
            this.getNavigation().moveTo(fleeTo.getX(), fleeTo.getY(), fleeTo.getZ(), 1.5);
        }

        // Kaçarken can azsa yemek ye
        if (hasFood() && this.getHealth() < this.getMaxHealth() * 0.5f) {
            eatFood();
        }
    }

    // ── İYİLEŞME ─────────────────────────────────────────────────────────

    private void tickHealing() {
        if (survivalTimer > 200 || this.getHealth() >= this.getMaxHealth() * 0.9f) {
            survivalState = SurvivalState.IDLE;
            return;
        }

        // Yemek ye (regeneration için)
        if (survivalTimer % 40 == 0 && hasFood()) {
            eatFood();
            broadcastToNearby("§a🍖 İyileşmek için yiyorum... (" + (int)this.getHealth() + "/" + (int)this.getMaxHealth() + ")");
        }

        // Tehlike gelirse kaç
        if (hasNearbyHostile(8)) {
            survivalState = SurvivalState.FLEEING;
            survivalTimer = 0;
        }
    }

    // ── YİYECEK YÖNETİMİ ─────────────────────────────────────────────────

    private void tickEating() {
        if (hasFood()) {
            eatFood();
            broadcastToNearby("§a🍖 Yiyorum, mide dolu olsun!");
        }
        survivalState = SurvivalState.IDLE;
    }

    private boolean hasFood() {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            var st = this.inventory.getItem(i);
            if (!st.isEmpty() && st.getItem().isEdible()) return true;
        }
        // El slotlarına da bak
        for (var slot : this.getHandSlots())
            if (!slot.isEmpty() && slot.getItem().isEdible()) return true;
        return false;
    }

    private void eatFood() {
        // En iyi yemeği bul ve ye (envanter + el)
        ItemStack bestFood = ItemStack.EMPTY;
        int bestNutrition = 0;
        int bestSlot = -1;

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            var st = this.inventory.getItem(i);
            if (st.isEmpty() || !st.getItem().isEdible()) continue;
            var fp = st.getItem().getFoodProperties(st, this);
            int n = fp != null ? fp.getNutrition() : 1;
            if (n > bestNutrition) { bestNutrition = n; bestFood = st; bestSlot = i; }
        }

        if (!bestFood.isEmpty() && bestSlot >= 0) {
            this.inventory.getItem(bestSlot).shrink(1);
            // Can yenile (simülasyon — gerçek yeme animasyonu yok mob'da)
            this.heal(Math.min(bestNutrition * 0.5f, this.getMaxHealth() - this.getHealth()));
        }
    }

    // ── GECE SIĞINAK ─────────────────────────────────────────────────────

    private void tickBuildingShelter() {
        if (survivalTimer > 400) {
            // Timeout — elle idare et
            broadcastToNearby("§c😅 Sığınak yapamadım, olduğum yerde kalıyorum.");
            survivalState = SurvivalState.IDLE;
            return;
        }

        // Yakında kapalı bir alan var mı? (köy evi, mağara vb.)
        BlockPos existingShelter = findNearestShelter(32);
        if (existingShelter != null) {
            // Git içeri
            double dist = this.distanceToSqr(Vec3.atCenterOf(existingShelter));
            if (dist > 4) {
                if (!this.getNavigation().isInProgress())
                    this.getNavigation().moveTo(existingShelter.getX()+0.5, existingShelter.getY(), existingShelter.getZ()+0.5, 1.2);
                return;
            }
            shelterPos = existingShelter;
            shelterBuilt = true;
            broadcastToNearby("§a🏠 Sığınak buldum, içerideyim!");
            survivalState = SurvivalState.IDLE;
            return;
        }

        // Sığınak yok — basit barınak yap (3x3 duvar + çatı)
        if (survivalTimer == 1) {
            broadcastToNearby("§6🏗️ Basit barınak yapıyorum...");
            buildSimpleShelter();
        }
    }

    /** 3x3 basit barınak inşa et */
    private void buildSimpleShelter() {
        if (this.level().isClientSide()) return;
        BlockPos base = this.blockPosition();

        // Envanterde tahta var mı?
        if (countItem("log") < 9 && countItem("planks") < 9) {
            broadcastToNearby("§c🪵 Barınak için yeterli malzeme yok!");
            survivalState = SurvivalState.IDLE;
            return;
        }

        // 3x3 duvarlar (yükseklik 3)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 2; y++) {
                    BlockPos bp = base.offset(x, y, z);
                    if (x == 0 && z == 0) continue; // İç alan boş
                    if (this.level().isEmptyBlock(bp)) {
                        this.level().setBlockAndUpdate(bp, Blocks.OAK_PLANKS.defaultBlockState());
                    }
                }
            }
        }
        // Çatı
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                this.level().setBlockAndUpdate(base.offset(x, 3, z), Blocks.OAK_PLANKS.defaultBlockState());

        shelterPos = base;
        shelterBuilt = true;
        broadcastToNearby("§a🏠 Barınak tamamlandı! İçerideyim.");
        survivalState = SurvivalState.SLEEPING;
    }

    // ── UYKU ─────────────────────────────────────────────────────────────

    private void tickSleeping() {
        // Gündüz olduysa uyan
        if (!this.level().isNight()) {
            broadcastToNearby("§e☀️ Günaydın! Yeni bir gün başlıyor.");
            survivalState = SurvivalState.MORNING_ROUTINE;
            survivalTimer = 0;
            return;
        }

        // Yakında yatak var mı?
        BlockPos bed = findBlock("bed", 8);
        if (bed != null && survivalTimer == 1) {
            double dist = this.distanceToSqr(Vec3.atCenterOf(bed));
            if (dist > 4) {
                this.getNavigation().moveTo(bed.getX()+0.5, bed.getY(), bed.getZ()+0.5, 1.0);
            } else {
                broadcastToNearby("§9😴 İyi geceler, sabaha kadar uyuyorum...");
            }
        }

        // Gece boyunca bekle (düşman gelirse uyan)
        if (hasNearbyHostile(10)) {
            broadcastToNearby("§c😱 DÜŞMAN! Uyanıyorum!");
            survivalState = SurvivalState.FLEEING;
            survivalTimer = 0;
        }
    }

    // ── SABAH RUTİNİ ─────────────────────────────────────────────────────

    private void tickMorningRoutine() {
        if (survivalTimer > 60) {
            survivalState = SurvivalState.IDLE;
            return;
        }

        if (survivalTimer == 1) {
            broadcastToNearby("§e☀️ Günaydın kanka! Hazır mısın? 💪");
            // Sabah yemek ye
            if (hasFood()) {
                eatFood();
                broadcastToNearby("§a🍖 Kahvaltı yapıyorum, güne hazırım!");
            }
            // Sığınaktan çık
            if (shelterPos != null) {
                BlockPos outside = shelterPos.offset(2, 0, 0);
                this.getNavigation().moveTo(outside.getX(), outside.getY(), outside.getZ(), 1.0);
            }
        }
    }

    // ── YARDIMCI KONTROLLER ───────────────────────────────────────────────

    public boolean hasNearbyHostile(int radius) {
        return !this.level().getEntitiesOfClass(Monster.class,
            new AABB(this.blockPosition()).inflate(radius)).isEmpty();
    }

    private LivingEntity findNearestHostile(int radius) {
        return this.level().getEntitiesOfClass(Monster.class,
            new AABB(this.blockPosition()).inflate(radius))
            .stream().min(java.util.Comparator.comparingDouble(e -> e.distanceTo(this)))
            .orElse(null);
    }

    /** Etrafta kapalı bir alan var mı? (ışık seviyesi düşük = içeride) */
    private boolean isInsideShelter() {
        return this.level().getMaxLocalRawBrightness(this.blockPosition()) < 7;
    }

    /** En yakın kapalı alanı bul (köy evi, mağara girişi) */
    private BlockPos findNearestShelter(int radius) {
        BlockPos pos = this.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx += 3) {
            for (int dz = -radius; dz <= radius; dz += 3) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    // İçerisi boş, çatısı var, ışık az = sığınak
                    if (this.level().isEmptyBlock(check) &&
                        !this.level().isEmptyBlock(check.above()) &&
                        this.level().getMaxLocalRawBrightness(check) < 8) {
                        double d = pos.distSqr(check);
                        if (d < bestDist) { bestDist = d; best = check; }
                    }
                }
            }
        }
        return best;
    }

    // ── Dünya Algısı ──────────────────────────────────────────────────────────

    /**
     * FoxAI'nin etrafındaki dünyayı analiz eder ve AI'ye gönderilecek context üretir.
     * Server-side çalışır, her 2 saniyede bir güncellenir.
     */
    public String buildWorldContext() {
        if (this.level().isClientSide()) return "";
        StringBuilder ctx = new StringBuilder();
        BlockPos pos = this.blockPosition();

        // ── FoxAI Durumu ──
        ctx.append("=== FOXAİ DURUMU ===
");
        ctx.append("Konum: ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append("
");
        ctx.append("Can: ").append((int) this.getHealth()).append("/").append((int) this.getMaxHealth()).append("
");
        ctx.append("Meşgul: ").append(isBusy ? "EVET (" + actionQueue.size() + " aksiyon bekliyor)" : "hayır").append("
");

        // Elimdeki alet
        var mainHand = this.getMainHandItem();
        if (!mainHand.isEmpty())
            ctx.append("Elimde: ").append(mainHand.getDisplayName().getString()).append("
");

        // Zırh
        StringBuilder armor = new StringBuilder();
        this.getArmorSlots().forEach(s -> { if (!s.isEmpty()) armor.append(s.getDisplayName().getString()).append(", "); });
        if (!armor.isEmpty()) ctx.append("Zırh: ").append(armor).append("
");

        // Envanter özeti (ilk 9 slot)
        StringBuilder inv = new StringBuilder();
        for (int i = 0; i < Math.min(9, this.inventory.getContainerSize()); i++) {
            var st = this.inventory.getItem(i);
            if (!st.isEmpty()) inv.append(st.getDisplayName().getString()).append("x").append(st.getCount()).append(", ");
        }
        if (!inv.isEmpty()) ctx.append("Envanter: ").append(inv).append("
");

        // ── Etraftaki Bloklar ──
        ctx.append("
=== ETRAFTAKİ BLOKLAR (8 blok radius) ===
");
        java.util.Map<String, Integer> blockCounts = new java.util.TreeMap<>();
        boolean nearLava = false, nearWater = false, nearVoid = false;
        int radius = 8;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos bp = pos.offset(dx, dy, dz);
                    var state = this.level().getBlockState(bp);
                    if (state.isAir()) continue;
                    String id = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getKey(state.getBlock()) != null
                        ? net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).getPath()
                        : "unknown";
                    // Kategorize et
                    String cat = categorizeBlock(id);
                    if (cat == null) continue;
                    if (id.contains("lava")) { nearLava = true; continue; }
                    if (id.contains("water")) { nearWater = true; continue; }
                    blockCounts.merge(cat, 1, Integer::sum);
                }
            }
        }
        // Uçurum kontrolü (ayakların altında 5 blok boşluk)
        for (int dy = -1; dy >= -5; dy--) {
            if (this.level().getBlockState(pos.offset(0, dy, 0)).isAir()) {
                if (dy <= -4) nearVoid = true;
            } else break;
        }

        blockCounts.forEach((cat, count) -> ctx.append(cat).append(": ").append(count).append(" blok
"));
        if (nearLava) ctx.append("⚠ YAKINDA LAV VAR!
");
        if (nearWater) ctx.append("Yakında su var
");
        if (nearVoid) ctx.append("⚠ YAKINDA UÇURUM VAR!
");

        // ── Yakındaki Entityler ──
        ctx.append("
=== YAKIN ENTİTYLER ===
");
        var entities = this.level().getEntitiesOfClass(
            net.minecraft.world.entity.LivingEntity.class,
            new AABB(pos).inflate(16),
            e -> e != this && e.isAlive()
        );

        int hostileCount = 0, passiveCount = 0, playerCount = 0;
        StringBuilder hostiles = new StringBuilder();
        StringBuilder players = new StringBuilder();
        StringBuilder passives = new StringBuilder();

        for (var e : entities) {
            double dist = Math.round(this.distanceTo(e) * 10.0) / 10.0;
            String name = e.getName().getString();
            String distStr = " (" + dist + " blok)";

            if (e instanceof net.minecraft.world.entity.player.Player p) {
                playerCount++;
                players.append(p.getName().getString()).append(distStr).append(", ");
            } else if (e instanceof net.minecraft.world.entity.monster.Monster) {
                hostileCount++;
                hostiles.append(name).append(distStr).append(", ");
            } else {
                passiveCount++;
                if (passiveCount <= 5) passives.append(name).append(distStr).append(", ");
            }
        }

        if (hostileCount > 0) ctx.append("⚔ Düşmanlar: ").append(hostiles).append("
");
        if (playerCount > 0) ctx.append("👤 Oyuncular: ").append(players).append("
");
        if (passiveCount > 0) ctx.append("🐄 Pasif: ").append(passives).append(passiveCount > 5 ? "+" + (passiveCount-5) + " daha" : "").append("
");
        if (entities.isEmpty()) ctx.append("Etrafta kimse yok
");

        // ── Çevre Durumu ──
        ctx.append("
=== ÇEVRE ===
");
        ctx.append("Gece: ").append(this.level().isNight() ? "EVET (tehlikeli!)" : "hayır").append("
");
        ctx.append("Yağmur: ").append(this.level().isRaining() ? "EVET" : "hayır").append("
");
        ctx.append("Işık seviyesi: ").append(this.level().getMaxLocalRawBrightness(pos)).append("/15
");

        // Yakında crafting table var mı?
        BlockPos table = findBlock("crafting_table", 16);
        ctx.append("Crafting table: ").append(table != null ? "VAR (" + (int)Math.sqrt(this.blockPosition().distSqr(table)) + " blok)" : "yok").append("
");

        // Yakında yatakta uyku var mı?
        BlockPos bed = findBlock("bed", 16);
        ctx.append("Yatak: ").append(bed != null ? "VAR" : "yok").append("
");

        return ctx.toString();
    }

    /** Blok ID'sini anlamlı bir kategoriye çevirir. null = önemsiz */
    private String categorizeBlock(String id) {
        if (id.contains("log") || id.contains("wood")) return "Ağaç/Log";
        if (id.contains("leaves")) return null; // yaprakları say
        if (id.contains("diamond_ore")) return "💎 Elmas Cevheri";
        if (id.contains("iron_ore") || id.contains("deepslate_iron")) return "Demir Cevheri";
        if (id.contains("gold_ore")) return "Altın Cevheri";
        if (id.contains("coal_ore")) return "Kömür Cevheri";
        if (id.contains("emerald_ore")) return "Zümrüt Cevheri";
        if (id.contains("_ore")) return "Cevher";
        if (id.contains("stone") || id.contains("cobblestone") || id.contains("deepslate")) return "Taş";
        if (id.contains("dirt") || id.contains("grass") || id.contains("podzol")) return "Toprak";
        if (id.contains("sand") || id.contains("gravel")) return "Kum/Çakıl";
        if (id.contains("chest")) return "📦 Sandık";
        if (id.contains("crafting_table")) return "🔨 Crafting Table";
        if (id.contains("furnace")) return "🔥 Fırın";
        if (id.contains("bed")) return "🛏 Yatak";
        if (id.contains("door") || id.contains("fence") || id.contains("wall")) return "Yapı Bloğu";
        if (id.equals("air") || id.contains("void")) return null;
        return null; // önemsiz blokları atla
    }

    // ── Yardımcı Metotlar ──────────────────────────────────────────────────

    public void broadcastToNearby(String msg) {
        Component comp = Component.literal("§8[§aFox§8] §f" + msg);
        this.level().getEntitiesOfClass(Player.class,
            new AABB(this.blockPosition()).inflate(32))
            .forEach(p -> p.sendSystemMessage(comp));
    }

    private void whisperTo(String name, String msg) {
        if (name == null || msg == null) return;
        Player p = findPlayer(name);
        if (p != null) p.sendSystemMessage(
            Component.literal("§8[§aFox→§f" + p.getName().getString() + "§8] §7" + msg));
    }

    private Player findPlayer(String name) {
        return this.level().getEntitiesOfClass(Player.class,
            new AABB(this.blockPosition()).inflate(48)).stream()
            .filter(p -> name == null || name.isEmpty() || "player".equalsIgnoreCase(name) ||
                p.getName().getString().equalsIgnoreCase(name))
            .findFirst().orElse(null);
    }

    private boolean hasItemInInventory(String partialId) {
        for (var slot : this.getHandSlots())
            if (!slot.isEmpty() && slot.getDescriptionId().toLowerCase().contains(partialId)) return true;
        for (var slot : this.getArmorSlots())
            if (!slot.isEmpty() && slot.getDescriptionId().toLowerCase().contains(partialId)) return true;
        return false;
    }

    // ── Statik Yardımcılar ─────────────────────────────────────────────────

    private static String str(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : "";
    }
    private static int integer(JsonObject o, String k, int def) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : def;
    }
    private static String coal(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }
    private static String dirTR(String d) {
        if (d == null) return "ileri";
        return switch (d.toLowerCase()) {
            case "north","forward","ileri" -> "kuzeye";
            case "south","back","geri"     -> "güneye";
            case "east","right","sağ"      -> "doğuya";
            case "west","left","sol"       -> "batıya";
            case "up","yukarı"  -> "yukarı";
            case "down","aşağı" -> "aşağı";
            default -> d;
        };
    }

    // ── Renderer (Client Only) ─────────────────────────────────────────────

    public static class Model extends HumanoidModel<FoxAIEntity> {
        public Model(net.minecraft.client.model.geom.ModelPart root) { super(root); }
    }

    public static class Renderer extends HumanoidMobRenderer<FoxAIEntity, Model> {
        private static final ResourceLocation SKIN =
            new ResourceLocation(AiPlayerMod.MOD_ID, "textures/entity/foxai.png");

        public Renderer(EntityRendererProvider.Context ctx) {
            super(ctx, new Model(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
            this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
        }

        @Override
        public ResourceLocation getTextureLocation(FoxAIEntity entity) { return SKIN; }
    }
}

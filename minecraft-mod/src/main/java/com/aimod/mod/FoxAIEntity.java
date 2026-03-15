package com.aimod.mod;

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

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FoxAIEntity extends PathfinderMob {

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

    // ── Constructor ────────────────────────────────────────────────────────
    public FoxAIEntity(EntityType<? extends FoxAIEntity> type, Level level) {
        super(type, level);
        this.setCustomName(Component.literal("FoxAI [Bot]"));
        this.setCustomNameVisible(true);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(true);
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
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new FoxAIFollowGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true));
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

    @Override
    public void tick() {
        super.tick();
        if (guardTarget != null && guardTarget.isAlive() && !isBusy) {
            LivingEntity threat = findNearbyThreat();
            if (threat != null) this.setTarget(threat);
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
                case "mine"   -> 120;
                case "move", "sprint" -> 80;
                case "attack" -> 60;
                case "wait"   -> 60;
                case "eat"    -> 50;
                case "craft", "smelt" -> 50;
                default -> 20;
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
            case "stop", "stop_follow" -> { clearQueue(); yield true; }

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
            case "equip"  -> { broadcastToNearby("⚔️ kuşandım: §e" + coal(target,"?")); yield true; }
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
            case "farm"   -> { broadcastToNearby("🌾 çiftlik işliyorum..."); yield true; }
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

    private boolean executeMove(String dir, int steps, String targetName, double speed) {
        if (actionTimer == 0) {
            BlockPos dest = resolveDestination(dir, steps, targetName);
            if (dest != null) {
                this.getNavigation().moveTo(dest.getX()+0.5, dest.getY(), dest.getZ()+0.5, speed);
                broadcastToNearby("🚶 " + dirTR(dir) + " gidiyorum...");
            }
        }
        return !this.getNavigation().isInProgress() && actionTimer > 5;
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

    // ── Kazma ─────────────────────────────────────────────────────────────

    private boolean executeMine(String blockName, int qty, int radius) {
        if (actionTimer == 0) broadcastToNearby("⛏️ kazıyorum: §e" + coal(blockName,"blok") + " §7x"+qty);

        BlockPos found = findBlock(blockName, radius);
        if (found == null) {
            if (actionTimer > 15) { broadcastToNearby("⛏️ §e"+coal(blockName,"blok")+" §7bulunamadı."); return true; }
            return false;
        }

        double dist = this.distanceToSqr(Vec3.atCenterOf(found));
        if (dist > 9.0) {
            this.getNavigation().moveTo(found.getX()+0.5, found.getY(), found.getZ()+0.5, 1.1);
            return false;
        }

        if (!this.level().isClientSide()) {
            String blockName2 = this.level().getBlockState(found).getBlock().getName().getString();
            this.level().destroyBlock(found, true, this);
            broadcastToNearby("⛏️ §e" + blockName2 + " §7kırıldı!");
        }
        return qty <= 1;
    }

    private BlockPos findBlock(String name, int radius) {
        BlockPos origin = this.blockPosition();
        String search = name == null ? "" : name.toLowerCase()
            .replace("ahşap","log").replace("taş","cobblestone")
            .replace("elmas","diamond_ore").replace("demir","iron_ore")
            .replace("kömür","coal_ore");

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

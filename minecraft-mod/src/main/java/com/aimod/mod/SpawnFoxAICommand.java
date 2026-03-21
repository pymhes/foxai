package com.aimod.mod;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AiPlayerMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SpawnFoxAICommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("spawn")
                .then(Commands.literal("foxai")
                    .executes(ctx -> {
                        CommandSourceStack source = ctx.getSource();

                        if (!(source.getEntity() instanceof ServerPlayer player)) {
                            source.sendFailure(Component.literal(
                                "§cBu komutu sadece oyuncular kullanabilir!"
                            ));
                            return 0;
                        }

                        ServerLevel level = player.serverLevel();
                        BlockPos pos = player.blockPosition().offset(2, 0, 0);

                        // Spawn yeri boş mu kontrol et
                        BlockPos spawnPos = findSafeSpot(level, pos);

                        FoxAIEntity foxai = FoxAIEntity.FOXAI.get().create(level);
                        if (foxai == null) {
                            source.sendFailure(Component.literal(
                                "§cFoxAI oluşturulamadı!"
                            ));
                            return 0;
                        }

                        foxai.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 
                            player.getYRot(), 0);
                        foxai.setOwner(player);
                        foxai.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
                            MobSpawnType.COMMAND, null, null);

                        level.addFreshEntity(foxai);
                        FoxAILog.info("FoxAI spawn edildi → konum: " + spawnPos
                            + " | owner: " + player.getName().getString()
                            + " | UUID: " + player.getUUID());

                        player.displayClientMessage(
                            Component.literal("§a✦ FoxAI çağrıldı! §7Sana yardım etmeye hazırım!"),
                            false
                        );
                        player.displayClientMessage(
                            Component.literal("§7Kullanım: §e!ai <komut> §7(örn: §e!ai ev yap§7, §e!ai beni takip et§7)"),
                            false
                        );

                        AiPlayerMod.LOGGER.info("[AI Player Mod] FoxAI {} konumuna spawn edildi.", spawnPos);
                        return 1;
                    })
                )
        );

        AiPlayerMod.LOGGER.info("[AI Player Mod] /spawn foxai komutu kaydedildi.");
    }

    private static BlockPos findSafeSpot(ServerLevel level, BlockPos start) {
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos check = start.offset(0, dy, 0);
            if (level.getBlockState(check).isAir() && level.getBlockState(check.above()).isAir()) {
                return check;
            }
        }
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos check = start.offset(0, dy, 0);
            if (!level.getBlockState(check).isAir() && level.getBlockState(check.above()).isAir()) {
                return check.above();
            }
        }
        return start;
    }
}

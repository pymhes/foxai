package com.aimod.mod;

import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.stream.Collectors;

public class ModDetector {

    private static List<String> cachedMods = null;

    public static List<String> getLoadedMods() {
        if (cachedMods != null) return cachedMods;
        cachedMods = ModList.get().getMods().stream()
            .map(info -> info.getModId() + ":" + info.getDisplayName())
            .filter(m -> !m.startsWith("minecraft:") && !m.startsWith("forge:"))
            .collect(Collectors.toList());
        AiPlayerMod.LOGGER.info("[FoxAI] {} mod tespit edildi.", cachedMods.size());
        return cachedMods;
    }

    public static String getModsAsString() {
        List<String> mods = getLoadedMods();
        if (mods.isEmpty()) return "";
        return String.join(", ", mods);
    }

    public static boolean hasMod(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static String buildModContext() {
        List<String> mods = getLoadedMods();
        if (mods.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("=== YÜKLÜ MODLAR (").append(mods.size()).append(" adet) ===\n");
        mods.forEach(m -> sb.append("- ").append(m).append("\n"));
        return sb.toString();
    }
}

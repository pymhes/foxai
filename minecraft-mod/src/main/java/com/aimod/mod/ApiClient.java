package com.aimod.mod;

import com.google.gson.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ApiClient {

    public static class ModChatResponse {
        public String reply;
        public String plan;
        public String emotion;
        public JsonArray actions;
        public boolean understood;
        public boolean isConversation;
    }

    /** Eski API — geriye dönük uyumluluk */
    public static ModChatResponse sendChatMessage(String message, String playerName, String context) {
        return sendChatMessageFull(message, playerName, context, false, null);
    }

    /** Yeni API — sohbet modu + mod listesi + duygu desteği */
    public static ModChatResponse sendChatMessageFull(
            String message, String playerName, String context,
            boolean isConversation, List<String> loadedMods) {
        try {
            URL url = new URL(ModConfig.getApiUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(25000);

            JsonObject body = new JsonObject();
            body.addProperty("message", message);
            body.addProperty("playerName", playerName);
            body.addProperty("isConversation", isConversation);
            if (context != null && !context.isEmpty()) body.addProperty("context", context);

            // Yüklü modlar listesi
            if (loadedMods != null && !loadedMods.isEmpty()) {
                JsonArray modsArr = new JsonArray();
                loadedMods.forEach(modsArr::add);
                body.add("loadedMods", modsArr);
            }

            byte[] bodyBytes = new Gson().toJson(body).getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return errorResponse("Sunucudan yanıt yok (HTTP " + status + ")");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            if (status < 200 || status >= 300) return errorResponse("Sunucu hatası " + status + ": " + sb);

            JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
            ModChatResponse r = new ModChatResponse();
            r.reply          = json.has("reply") ? json.get("reply").getAsString() : "anlaşıldı!";
            r.plan           = json.has("plan") && !json.get("plan").isJsonNull() ? json.get("plan").getAsString() : null;
            r.emotion        = json.has("emotion") && !json.get("emotion").isJsonNull() ? json.get("emotion").getAsString() : null;
            r.actions        = json.has("actions") ? json.get("actions").getAsJsonArray() : new JsonArray();
            r.understood     = !json.has("understood") || json.get("understood").getAsBoolean();
            r.isConversation = json.has("isConversation") && json.get("isConversation").getAsBoolean();
            return r;

        } catch (Exception e) {
            AiPlayerMod.LOGGER.error("[FoxAI] API hatası: {}", e.getMessage());
            return errorResponse("ya bağlantı koptu knk 😬  (" + e.getMessage() + ")");
        }
    }

    private static ModChatResponse errorResponse(String msg) {
        ModChatResponse r = new ModChatResponse();
        r.reply = msg; r.plan = null; r.emotion = null;
        r.actions = new JsonArray(); r.understood = false;
        return r;
    }
}

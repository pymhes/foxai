# AI Player Mod - Workspace

## Overview

Forge 1.20.1 için yapay zeka destekli Minecraft oyuncu modu. Chat'te yazdığınız Türkçe/İngilizce komutları anlayıp uygulayan bir bot modu.

## Stack

- **Monorepo tool**: pnpm workspaces
- **Node.js version**: 24
- **Package manager**: pnpm
- **TypeScript version**: 5.9
- **API framework**: Express 5
- **Database**: PostgreSQL + Drizzle ORM
- **Validation**: Zod (`zod/v4`), `drizzle-zod`
- **API codegen**: Orval (from OpenAPI spec)
- **AI**: OpenAI GPT-5.2 via Replit AI Integrations
- **Build**: esbuild (CJS bundle)
- **Frontend**: React + Vite, Recharts, Framer Motion

## Structure

```text
/
├── artifacts/
│   ├── api-server/         # Express API sunucusu
│   └── ai-mod-dashboard/   # React dashboard (preview)
├── lib/
│   ├── api-spec/           # OpenAPI spec + Orval codegen config
│   ├── api-client-react/   # Generated React Query hooks
│   ├── api-zod/            # Generated Zod schemas from OpenAPI
│   ├── db/                 # Drizzle ORM schema + DB connection
│   ├── integrations-openai-ai-server/  # OpenAI server integration
│   └── integrations-openai-ai-react/   # OpenAI React integration
├── minecraft-mod/          # Forge 1.20.1 Java mod source
│   ├── build.gradle
│   ├── settings.gradle
│   ├── KURULUM.md          # Installation guide in Turkish
│   └── src/main/java/com/aimod/mod/
│       ├── AiPlayerMod.java      # Mod entry point
│       ├── ChatEventHandler.java # Chat event listener
│       ├── ActionExecutor.java   # Executes AI actions in-game
│       ├── ApiClient.java        # HTTP client for the API
│       └── ModConfig.java        # Configuration management
└── scripts/
```

## How It Works

1. Player types `!ai <command>` in Minecraft chat
2. `ChatEventHandler` captures the message, cancels it from appearing in chat
3. Sends to API server (`POST /api/mod/chat`) with player context (health, position, inventory, etc.)
4. API server uses GPT-5.2 to parse the natural language command
5. Returns structured JSON with `reply` (text) and `actions` (array)
6. `ActionExecutor` executes each action in sequence (mine, move, attack, etc.)

## API Endpoints

- `POST /api/mod/chat` — Main AI endpoint (processes chat commands)
- `GET /api/mod/logs` — Recent activity logs
- `GET /api/mod/config` — Mod configuration
- `PATCH /api/mod/config` — Update configuration
- `GET /api/mod/commands` — List supported commands

## Supported Actions

move, attack, mine, place, craft, eat, sleep, follow, stop, say, look, jump, sneak, sprint, use_item, drop_item, pickup_item, open_chest, close_chest, equip, unequip

## Dashboard Features

- Live chat simulator (test commands without Minecraft)
- Real-time activity log
- Config panel (toggle mod, set prefix, language, personality)
- Supported commands reference grid
- Stats overview

## Building the Java Mod

```bash
cd minecraft-mod
./gradlew build
# Output: build/libs/aiplayermod-1.0.0.jar
```

Then copy the JAR to your Minecraft mods/ folder.
Edit `config/aiplayermod.properties` to set the API URL.

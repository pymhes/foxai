import { Router, type IRouter } from "express";
import { eq, desc } from "drizzle-orm";
import { db, modLogsTable, modConfigTable } from "@workspace/db";
import {
  ProcessModChatBody,
  AddModLogBody,
  UpdateModConfigBody,
  GetModLogsQueryParams,
} from "@workspace/api-zod";
import { processModChat, SUPPORTED_COMMANDS } from "../../lib/ai-mod.js";

const router: IRouter = Router();

router.post("/mod/chat", async (req, res): Promise<void> => {
  const parsed = ProcessModChatBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.message });
    return;
  }

  const { message, playerName, context, isConversation, loadedMods } = parsed.data as any;

  const result = await processModChat(
    message,
    playerName,
    context ?? undefined,
    isConversation ?? false,
    loadedMods ?? []
  );

  await db.insert(modLogsTable).values({
    playerName,
    message,
    reply: result.reply,
    actions: JSON.stringify(result.actions),
    success: result.understood,
  });

  res.json(result);
});

router.get("/mod/logs", async (req, res): Promise<void> => {
  const query = GetModLogsQueryParams.safeParse(req.query);
  const limit = query.success && query.data.limit ? query.data.limit : 50;

  const logs = await db
    .select()
    .from(modLogsTable)
    .orderBy(desc(modLogsTable.timestamp))
    .limit(limit);

  res.json(logs);
});

router.post("/mod/logs", async (req, res): Promise<void> => {
  const parsed = AddModLogBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.message });
    return;
  }

  const [log] = await db.insert(modLogsTable).values(parsed.data).returning();
  res.status(201).json(log);
});

router.get("/mod/config", async (_req, res): Promise<void> => {
  let configs = await db.select().from(modConfigTable).limit(1);

  if (configs.length === 0) {
    const [defaultConfig] = await db
      .insert(modConfigTable)
      .values({})
      .returning();
    configs = [defaultConfig];
  }

  res.json(configs[0]);
});

router.patch("/mod/config", async (req, res): Promise<void> => {
  const parsed = UpdateModConfigBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.message });
    return;
  }

  let configs = await db.select().from(modConfigTable).limit(1);

  if (configs.length === 0) {
    const [newConfig] = await db
      .insert(modConfigTable)
      .values({ ...parsed.data })
      .returning();
    res.json(newConfig);
    return;
  }

  const [updated] = await db
    .update(modConfigTable)
    .set({ ...parsed.data, updatedAt: new Date() })
    .where(eq(modConfigTable.id, configs[0].id))
    .returning();

  res.json(updated);
});

router.get("/mod/commands", async (_req, res): Promise<void> => {
  res.json(SUPPORTED_COMMANDS);
});

export default router;

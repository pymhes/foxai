import { pgTable, text, serial, boolean, integer, timestamp } from "drizzle-orm/pg-core";
import { createInsertSchema } from "drizzle-zod";
import { z } from "zod/v4";

export const modConfigTable = pgTable("mod_config", {
  id: serial("id").primaryKey(),
  enabled: boolean("enabled").notNull().default(true),
  triggerPrefix: text("trigger_prefix").notNull().default("!ai"),
  respondToAll: boolean("respond_to_all").notNull().default(false),
  language: text("language").notNull().default("tr"),
  personality: text("personality").notNull().default("Minecraft'ta yardımcı olan, eğlenceli ve nazik bir yapay zeka asistanısın."),
  maxActionsPerCommand: integer("max_actions_per_command").notNull().default(5),
  updatedAt: timestamp("updated_at").notNull().defaultNow(),
});

export const insertModConfigSchema = createInsertSchema(modConfigTable).omit({ id: true, updatedAt: true });
export type InsertModConfig = z.infer<typeof insertModConfigSchema>;
export type ModConfig = typeof modConfigTable.$inferSelect;

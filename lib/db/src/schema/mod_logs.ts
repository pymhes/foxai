import { pgTable, text, serial, boolean, timestamp } from "drizzle-orm/pg-core";
import { createInsertSchema } from "drizzle-zod";
import { z } from "zod/v4";

export const modLogsTable = pgTable("mod_logs", {
  id: serial("id").primaryKey(),
  playerName: text("player_name").notNull(),
  message: text("message").notNull(),
  reply: text("reply").notNull(),
  actions: text("actions").notNull().default("[]"),
  timestamp: timestamp("timestamp").notNull().defaultNow(),
  success: boolean("success").notNull().default(true),
});

export const insertModLogSchema = createInsertSchema(modLogsTable).omit({ id: true, timestamp: true });
export type InsertModLog = z.infer<typeof insertModLogSchema>;
export type ModLog = typeof modLogsTable.$inferSelect;

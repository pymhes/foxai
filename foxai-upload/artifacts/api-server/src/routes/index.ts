import { Router, type IRouter } from "express";
import healthRouter from "./health";
import modRouter from "./mod/index.js";
import openaiRouter from "./openai/index.js";

const router: IRouter = Router();

router.use(healthRouter);
router.use(modRouter);
router.use(openaiRouter);

export default router;

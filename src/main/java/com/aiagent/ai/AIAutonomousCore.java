package com.aiagent.ai;

import com.aiagent.AIAgentMod;
import com.aiagent.ai.ResponseParser.ParsedResponse;
import com.aiagent.api.AIApiClient;
import com.aiagent.brain.ActionRegistry;
import com.aiagent.brain.BehaviorStateMachine;
import com.aiagent.brain.BehaviorStateMachine.State;
import com.aiagent.brain.GameKnowledge;
import com.aiagent.config.AIConfig;
import com.aiagent.memory.MemoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 自主核心 - 让 AI 像真人一样生活
 *
 * 核心循环（每 tick）：
 * 1. 感知周围环境
 * 2. 检查是否需要思考（状态机决定）
 * 3. 如果需要 → 构建 prompt → 调 LLM → 解析响应 → 执行动作
 * 4. 如果不需要 → 执行当前状态的预定义行为
 *
 * 设计原则：
 * - 不是每 tick 都调 LLM（太贵），而是状态转换时调一次
 * - AI 有自己的意志，不是玩家的遥控器
 * - 危险情况走快速路径，不等 LLM
 * - 动作使用 MC 引擎的真实 API，不是 teleportTo 假移动
 */
public class AIAutonomousCore {

    private final ServerPlayer player;
    private final String name;
    private final BehaviorStateMachine stateMachine = new BehaviorStateMachine();
    private final ConversationManager conversation;
    private final MemoryManager memory;
    private final AIApiClient apiClient = new AIApiClient();
    private final GameKnowledge knowledge = new GameKnowledge();

    // 异步 LLM 调用：避免阻塞服务器主线程
    private volatile String pendingLLMResponse = null;
    private volatile boolean waitingForLLM = false;
    private String pendingChatSender = null; // null = 自主思考，非null = 回复玩家

    // 自主思考间隔（tick），不是每 tick 都想
    private int thinkCooldown = 0;
    private static final int DEFAULT_THINK_INTERVAL = 40; // 2 秒
    private int autonomousThinkInterval = DEFAULT_THINK_INTERVAL;

    // 当前正在执行的动作
    private String currentAction = null;
    private String currentActionTarget = null;
    private int actionTicks = 0;
    private static final int ACTION_TIMEOUT = 100; // 5 秒超时

    // 移动相关
    private static final double WALK_SPEED = 0.2;      // 每 tick 步长（格）
    private static final double RUN_SPEED = 0.35;       // 跑步步长
    private static final double STEP_HEIGHT = 0.6;      // 台阶高度（MC 默认）
    private static final double COLLISION_MARGIN = 0.05; // 碰撞检测余量

    // 挖掘相关
    private BlockPos miningTarget = null;
    private int miningTicks = 0;
    private int miningTotalTicks = 0;

    // 吃东西相关
    private int eatingTicks = 0;
    private static final int EAT_DURATION = 32; // MC 吃东西持续 32 tick

    // 攻击追踪
    private Entity attackTarget = null;
    private int attackCooldown = 0;

    /**
     * 服务器主线程执行器 - 用于异步 LLM 回调回到主线程
     */
    private java.util.concurrent.Executor serverExecutor;

    public AIAutonomousCore(ServerPlayer player, String name) {
        this.player = player;
        this.name = name;
        this.conversation = new ConversationManager(name);
        this.memory = new MemoryManager(name);

        // 加载记忆
        memory.load();

        // 生成随机性格
        String personality = conversation.generatePersonality();
        AIAgentMod.LOGGER.info("[{}] 诞生了！性格: {}", name, personality);

        // 记录诞生
        memory.addThought(0, "我刚刚来到这个世界。我的性格是：" + personality);
        memory.save();

        // 初始化游戏知识
        knowledge.setServer(player.getServer());

        // 初始化服务器主线程执行器（异步回调用）
        this.serverExecutor = command -> {
            if (player.getServer() != null) {
                player.getServer().execute(command);
            }
        };

        // 初始状态
        stateMachine.transition(State.IDLE, "刚刚召唤");
    }

    // ==================== 核心循环 ====================

    /**
     * 每 tick 调用 - AI 的心跳
     */
    public void tick() {
        // 更新状态机
        stateMachine.tick();

        // 检查异步 LLM 是否有结果回来了
        if (pendingLLMResponse != null) {
            String response = pendingLLMResponse;
            String sender = pendingChatSender;
            pendingLLMResponse = null;
            waitingForLLM = false;
            pendingChatSender = null;
            handleLLMResponse(response, sender);
        }

        // 死亡检测
        if (player.isDeadOrDying() || player.getHealth() <= 0) {
            handleDeath();
            return;
        }

        // 自动捡附近的掉落物
        tickPickupItems();

        // 处理进行中的挖掘
        if (miningTarget != null) {
            tickMining();
            return;
        }

        // 处理进行中的吃东西
        if (player.isUsingItem()) {
            tickEating();
            return;
        }

        // 攻击冷却
        if (attackCooldown > 0) attackCooldown--;

        // 检查生命值 - 危险检测
        if (checkDanger()) {
            return; // 危险状态有优先处理
        }

        // 冷却计数
        if (thinkCooldown > 0) {
            thinkCooldown--;
        }

        // 根据当前状态执行行为
        switch (stateMachine.getCurrentState()) {
            case IDLE -> tickIdle();
            case PLANNING -> tickPlanning();
            case EXECUTING -> tickExecuting();
            case VERIFYING -> tickVerifying();
            case CHATTING -> tickChatting();
            case EXPLORING -> tickExploring();
            case DANGER, FLEEING -> tickDanger();
        }
    }

    private void tickIdle() {
        if (thinkCooldown <= 0) {
            stateMachine.transition(State.PLANNING, "自主思考");
        }
    }

    private void tickPlanning() {
        // 如果已经在等 LLM 回复，不重复发
        if (waitingForLLM) return;

        String perception = buildPerception();
        String memoryContext = memory.getRelevantContext(perception);
        String prompt = conversation.buildAutonomousPrompt(
                perception, memoryContext, knowledge, player);

        // 异步调 LLM（不阻塞服务器主线程）
        waitingForLLM = true;
        pendingChatSender = null;
        apiClient.chatAsync(prompt).thenAcceptAsync(response -> {
            pendingLLMResponse = response;
        }, serverExecutor).exceptionallyAsync(e -> {
            AIAgentMod.LOGGER.warn("[{}] LLM 调用失败: {}", name, e.getMessage());
            pendingLLMResponse = null;
            waitingForLLM = false;
            // 回到 IDLE 状态
            stateMachine.transition(State.IDLE, "思考失败");
            thinkCooldown = autonomousThinkInterval * 2;
            return null;
        }, serverExecutor);
    }

    private void tickExecuting() {
        actionTicks++;
        if (actionTicks > ACTION_TIMEOUT) {
            AIAgentMod.LOGGER.debug("[{}] 动作执行超时: {}", name, currentAction);
            memory.addThought(player.tickCount, "做 " + currentAction + " 超时了，算了");
            stateMachine.transition(State.IDLE, "动作超时");
            currentAction = null;
            thinkCooldown = autonomousThinkInterval / 2;
            return;
        }

        boolean done = executeAction(currentAction, currentActionTarget);
        if (done) {
            stateMachine.transition(State.VERIFYING, currentAction + " 完成");
        }
    }

    private void tickVerifying() {
        stateMachine.transition(State.IDLE, "验证完成");
        thinkCooldown = autonomousThinkInterval / 3;
        currentAction = null;
    }

    private void tickChatting() {
        if (stateMachine.getStateTicks() > 20) {
            stateMachine.transition(State.IDLE, "聊完了");
            thinkCooldown = autonomousThinkInterval / 2;
        }
    }

    private void tickExploring() {
        if (stateMachine.getStateTicks() % 20 == 0) {
            walkRandom();
        }
        if (stateMachine.getStateTicks() > 200) {
            stateMachine.transition(State.IDLE, "探索够了");
            thinkCooldown = autonomousThinkInterval;
        }
    }

    private void tickDanger() {
        if (stateMachine.getCurrentState() == State.DANGER) {
            fleeFromDanger();
            stateMachine.transition(State.FLEEING, "逃跑中");
        }
        if (stateMachine.getStateTicks() > 60) {
            if (isSafe()) {
                stateMachine.transition(State.IDLE, "安全了");
                thinkCooldown = autonomousThinkInterval;
                memory.addThought(player.tickCount, "刚才好危险，不过我逃出来了");
            } else {
                fleeFromDanger();
                stateMachine.transition(State.DANGER, "还不安全");
            }
        }
    }

    // ==================== 聊天处理 ====================

    public void onChatReceived(String sender, String message) {
        if (!isAddressedToMe(message)) {
            if (shouldEavesdrop(message)) {
                memory.addThought(player.tickCount, "听到 " + sender + " 说: " + message);
            }
            return;
        }

        AIAgentMod.LOGGER.debug("[{}] 收到消息 from {}: {}", name, sender, message);

        String cleanMessage = cleanMessage(message);
        conversation.addTurn("user", cleanMessage);

        // 中断当前行为
        if (miningTarget != null) {
            miningTarget = null;
            miningTicks = 0;
        }
        stateMachine.transition(State.CHATTING, "和 " + sender + " 聊天");

        String perception = buildPerception();
        String memoryContext = memory.getRelevantContext(perception);
        String prompt = conversation.buildChatPrompt(
                sender, cleanMessage, perception,
                memoryContext, knowledge, player);

        // 异步调 LLM（不阻塞服务器主线程）
        waitingForLLM = true;
        pendingChatSender = sender;
        apiClient.chatAsync(prompt).thenAcceptAsync(response -> {
            pendingLLMResponse = response;
        }, serverExecutor).exceptionallyAsync(e -> {
            AIAgentMod.LOGGER.warn("[{}] 聊天 LLM 调用失败: {}", name, e.getMessage());
            sendChat("嗯...我刚走神了，你说啥？");
            waitingForLLM = false;
            pendingLLMResponse = null;
            return null;
        }, serverExecutor);

        memory.addThought(player.tickCount, sender + " 跟我说了: " + cleanMessage);
    }

    private void handleLLMResponse(String rawResponse, String chatSender) {
        ParsedResponse parsed = ResponseParser.parse(rawResponse);

        AIAgentMod.LOGGER.info("[{}] LLM 回复: reply={}, action={}, target={}",
                name,
                parsed.reply() != null ? parsed.reply().substring(0, Math.min(50, parsed.reply().length())) : "null",
                parsed.action(),
                parsed.actionTarget());

        if (parsed.thought() != null && !parsed.thought().isEmpty()) {
            memory.addThought(player.tickCount, parsed.thought());
            AIAgentMod.LOGGER.debug("[{}] 想法: {}", name, parsed.thought());
        }

        if (parsed.hasReply()) {
            String reply = parsed.reply();
            conversation.addTurn("assistant", reply);
            if (chatSender != null) {
                sendChat(reply);
            } else if (!reply.isEmpty()) {
                sendWhisper(reply);
            }
        }

        if (parsed.hasAction()) {
            currentAction = parsed.action();
            currentActionTarget = parsed.actionTarget();
            actionTicks = 0;
            stateMachine.transition(State.EXECUTING, currentAction);
        } else if (parsed.isChatOnly()) {
            if (chatSender != null) {
                stateMachine.transition(State.CHATTING, "聊完了");
            } else {
                stateMachine.transition(State.IDLE, "自言自语完了");
            }
            thinkCooldown = autonomousThinkInterval / 2;
        }
    }

    // ==================== 动作执行 ====================

    private boolean executeAction(String action, String target) {
        if (action == null) return true;
        AIAgentMod.LOGGER.debug("[{}] 执行动作: {} {}", name, action, target);

        return switch (action) {
            case "move"       -> executeMove(target);
            case "goto"       -> executeGoto(target);
            case "follow"     -> executeFollow(target);
            case "mine"       -> executeMine(target);
            case "chop"       -> executeChop(target);
            case "dig"        -> executeDig(target);
            case "place"      -> executePlace(target);
            case "attack"     -> executeAttack(target);
            case "flee"       -> { fleeFromDanger(); yield true; }
            case "craft"      -> executeCraft(target);
            case "eat"        -> executeEat(target);
            case "look"       -> executeLook(target);
            case "inventory"  -> executeInventory();
            case "health"     -> executeHealth();
            case "wait"       -> executeWait(target);
            case "sleep"      -> executeSleep();
            case "say"        -> executeSay(target);
            case "query"      -> executeQuery(target);
            case "none"       -> true;
            default -> {
                AIAgentMod.LOGGER.debug("[{}] 未知动作: {}", name, action);
                yield true;
            }
        };
    }

    // ==================== 移动系统（真实行走，非瞬移） ====================

    /**
     * 向指定方向走几步
     * 使用 MC 的移动系统：计算方向 → 检测碰撞 → 处理台阶 → 逐 tick 移动
     */
    private boolean executeMove(String target) {
        if (target == null) target = "forward";

        Direction facing = player.getDirection();
        Vec3 dir = switch (target.toLowerCase()) {
            case "north" -> new Vec3(0, 0, -1);
            case "south" -> new Vec3(0, 0, 1);
            case "east"  -> new Vec3(1, 0, 0);
            case "west"  -> new Vec3(-1, 0, 0);
            case "forward" -> Vec3.atLowerCornerOf(facing.getNormal());
            case "backward" -> Vec3.atLowerCornerOf(facing.getOpposite().getNormal());
            case "left" -> Vec3.atLowerCornerOf(facing.getCounterClockWise().getNormal());
            case "right" -> Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
            case "up" -> new Vec3(0, 1, 0);
            case "down" -> new Vec3(0, -1, 0);
            default -> Vec3.atLowerCornerOf(facing.getNormal());
        };

        // 走 5 步（比之前多）
        int moved = 0;
        for (int step = 0; step < 5; step++) {
            if (walkStep(dir)) {
                moved++;
            } else {
                // 碰到障碍，尝试跨上去
                if (!stepUp(dir)) {
                    break; // 走不动了
                }
                moved++;
            }
        }

        if (moved > 0) {
            AIAgentMod.LOGGER.debug("[{}] 向 {} 移动了 {} 步", name, target, moved);
        }
        return true;
    }

    /**
     * 走到指定坐标（路径点式，逐格走过去）
     */
    private boolean executeGoto(String target) {
        if (target == null) return true;

        try {
            String[] parts = target.split("[,\\s]+");
            if (parts.length >= 2) {
                double tx = Double.parseDouble(parts[0]);
                double tz = Double.parseDouble(parts[1]);
                double ty = parts.length >= 3 ? Double.parseDouble(parts[2]) : player.getY();

                double dx = tx - player.getX();
                double dy = ty - player.getY();
                double dz = tz - player.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist < 1.5) return true; // 到了

                // 向目标走多步（每 tick 最多走 5 步）
                Vec3 dir = new Vec3(dx / dist, 0, dz / dist);
                int moved = 0;
                for (int i = 0; i < 5; i++) {
                    if (walkStep(dir)) {
                        moved++;
                    } else {
                        if (!stepUp(dir)) {
                            // 跳不过去，绕路
                            Vec3 side = new Vec3(-dir.z, 0, dir.x);
                            walkStep(side);
                            break;
                        }
                        moved++;
                    }
                }
                if (moved > 0) {
                    AIAgentMod.LOGGER.debug("[{}] goto {} 移动了 {} 步, 剩余 {:.1f} 格",
                            name, target, moved, dist);
                }
                return false; // 还没到
            }
        } catch (NumberFormatException e) {
            // 解析失败，当玩家名处理
            return executeFollow(target);
        }
        return true;
    }

    /**
     * 跟随玩家（走到附近，不瞬移）
     */
    private boolean executeFollow(String target) {
        if (target == null) return true;
        ServerLevel level = player.serverLevel();
        Player targetPlayer = level.getServer().getPlayerList().getPlayerByName(target);
        if (targetPlayer == null || targetPlayer == player) return true;

        double dist = player.distanceTo(targetPlayer);
        if (dist <= 3) return true; // 够近了

        // 向目标走多步
        double dx = targetPlayer.getX() - player.getX();
        double dz = targetPlayer.getZ() - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0) {
            Vec3 dir = new Vec3(dx / len, 0, dz / len);
            for (int i = 0; i < 3; i++) {
                if (!walkStep(dir)) {
                    stepUp(dir);
                    break;
                }
            }
        }
        return false; // 继续跟
    }

    /**
     * 走一步：检测碰撞 → 处理台阶 → 移动
     * @return true 如果成功移动
     */
    private boolean walkStep(Vec3 dir) {
        double speed = WALK_SPEED;
        Vec3 move = dir.normalize().scale(speed);

        double newX = player.getX() + move.x;
        double newY = player.getY();
        double newZ = player.getZ() + move.z;

        ServerLevel level = player.serverLevel();
        BlockPos targetPos = BlockPos.containing(newX, newY, newZ);
        BlockPos belowPos = BlockPos.containing(newX, newY - 0.1, newZ);

        BlockState targetBlock = level.getBlockState(targetPos);
        BlockState belowBlock = level.getBlockState(belowPos);

        // 详细日志：看看碰撞检测到底在判断什么
        AIAgentMod.LOGGER.info("[{}] walkStep: 目标方块={} 是否阻挡={}, 脚下={} 是否实心={}",
                name,
                targetBlock.getBlock().getName().getString(),
                targetBlock.blocksMotion(),
                belowBlock.getBlock().getName().getString(),
                belowBlock.blocksMotion());

        // 碰撞检测：目标位置不能是固体，脚下必须有固体
        if (!targetBlock.blocksMotion() && belowBlock.blocksMotion()) {
            double oldX = player.getX();
            double oldZ = player.getZ();

            // 尝试多种移动方式
            player.absMoveTo(newX, newY, newZ);

            // 验证是否真的移动了
            double moved = Math.abs(player.getX() - oldX) + Math.abs(player.getZ() - oldZ);
            if (moved < 0.001) {
                // absMoveTo 没生效，尝试 setPos
                player.setPos(newX, newY, newZ);
                moved = Math.abs(player.getX() - oldX) + Math.abs(player.getZ() - oldZ);
                AIAgentMod.LOGGER.info("[{}] absMoveTo 没生效，尝试 setPos, 移动距离={}", name, moved);
            }

            if (moved > 0.001) {
                updateLookDirection(dir);
                // 广播位置更新给所有客户端
                broadcastMovement(oldX, player.getY(), oldZ);
                AIAgentMod.LOGGER.info("[{}] 移动成功: ({},{},{}) → ({},{},{})",
                        name, oldX, player.getY(), oldZ, player.getX(), player.getY(), player.getZ());
                return true;
            } else {
                AIAgentMod.LOGGER.info("[{}] 移动失败！absMoveTo 和 setPos 都没生效", name);
                return false;
            }
        }

        AIAgentMod.LOGGER.info("[{}] 碰撞阻挡: {} (blocksMotion={})",
                name, targetBlock.getBlock().getName().getString(), targetBlock.blocksMotion());
        return false;
    }

    /**
     * 尝试跨上一格高的台阶
     */
    private boolean stepUp(Vec3 dir) {
        double speed = WALK_SPEED;
        Vec3 move = dir.normalize().scale(speed);

        double newX = player.getX() + move.x;
        double stepY = player.getY() + STEP_HEIGHT;
        double newZ = player.getZ() + move.z;

        ServerLevel level = player.serverLevel();
        BlockPos stepPos = BlockPos.containing(newX, stepY, newZ);
        BlockState stepBlock = level.getBlockState(stepPos);

        // 检查上方是否可站
        if (!stepBlock.blocksMotion()) {
            double oldX = player.getX();
            double oldZ = player.getZ();

            player.absMoveTo(newX, stepY, newZ);

            double moved = Math.abs(player.getX() - oldX) + Math.abs(player.getZ() - oldZ);
            if (moved < 0.001) {
                player.setPos(newX, stepY, newZ);
            }

            updateLookDirection(dir);
            // 广播位置更新
            broadcastMovement(oldX, player.getY(), oldZ);
            AIAgentMod.LOGGER.debug("[{}] 跨台阶: {} → Y+{}", name, stepBlock.getBlock().getName().getString(), STEP_HEIGHT);
            return true;
        }
        return false;
    }

    /**
     * 碰撞检测：目标位置是否可通行
     * 检查脚部和头部两个位置是否有固体方块
     */
    private boolean canWalkTo(double x, double y, double z) {
        ServerLevel level = player.serverLevel();

        // 脚部位置
        BlockPos feetPos = BlockPos.containing(x, y, z);
        // 头部位置
        BlockPos headPos = BlockPos.containing(x, y + 1.5, z);
        // 脚下位置（必须有方块踩）
        BlockPos belowPos = BlockPos.containing(x, y - 0.1, z);

        BlockState feetBlock = level.getBlockState(feetPos);
        BlockState headBlock = level.getBlockState(headPos);
        BlockState belowBlock = level.getBlockState(belowPos);

        // 脚和头不能有固体方块，脚下必须有固体方块
        return !feetBlock.blocksMotion()
            && !headBlock.blocksMotion()
            && belowBlock.blocksMotion();
    }

    /**
     * 更新朝向（面向移动方向）
     */
    private void updateLookDirection(Vec3 dir) {
        if (dir.x == 0 && dir.z == 0) return;
        float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
    }

    /**
     * 随机走动（探索用）
     */
    private void walkRandom() {
        double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        Vec3 dir = new Vec3(Math.cos(angle), 0, Math.sin(angle));
        walkStep(dir);
    }

    // ==================== 挖掘系统（逐 tick 破坏，有进度） ====================

    /**
     * 挖掘附近的方块
     * 使用 MC 的 destroyBlock，先走近再挖
     */
    private boolean executeMine(String target) {
        if (target == null) return true;

        // 如果已经在挖，继续
        if (miningTarget != null) return false;

        ServerLevel level = player.serverLevel();
        BlockPos playerPos = player.blockPosition();

        // 搜索附近 10 格内的目标方块
        BlockPos found = findNearbyBlock(target, 10);
        if (found == null) {
            memory.addThought(player.tickCount, "附近找不到 " + target);
            return true;
        }

        // 走到方块旁边
        double dist = playerPos.distSqr(found);
        if (dist > 4) { // 距离 > 2 格
            Vec3 dir = Vec3.atCenterOf(found).subtract(player.position()).normalize();
            walkStep(dir);
            return false; // 还没到
        }

        // 自动切换到最佳工具
        autoSelectTool(level.getBlockState(found));

        // 开始挖掘
        BlockState state = level.getBlockState(found);
        float destroySpeed = state.getDestroySpeed(level, found);
        // 手持工具加成
        ItemStack mainHand = player.getMainHandItem();
        float toolSpeed = mainHand.getDestroySpeed(state);
        if (toolSpeed > 1.0f) {
            destroySpeed *= toolSpeed;
        }
        // 基础破坏时间（tick）
        int breakTicks = Math.max(1, (int) (30 / Math.max(destroySpeed, 0.1)));

        miningTarget = found;
        miningTicks = 0;
        miningTotalTicks = breakTicks;

        AIAgentMod.LOGGER.info("[{}] 开始挖掘 {} (速度={}, 需要{}tick)",
                name, state.getBlock().getName().getString(), destroySpeed, breakTicks);

        return false; // 挖掘需要时间
    }

    /**
     * 每 tick 更新挖掘进度
     */
    private void tickMining() {
        if (miningTarget == null) return;

        miningTicks++;

        // 挖掘完成
        if (miningTicks >= miningTotalTicks) {
            ServerLevel level = player.serverLevel();
            BlockState state = level.getBlockState(miningTarget);

            if (!state.isAir()) {
                // 使用引擎的 destroyBlock（会掉落物品、播放音效、更新方块）
                boolean destroyed = level.destroyBlock(miningTarget, true, player);
                if (destroyed) {
                    memory.addThought(player.tickCount, "挖掉了 " + state.getBlock().getName().getString());
                }
            }

            miningTarget = null;
            miningTicks = 0;
        }
    }

    private boolean executeChop(String target) {
        return executeMine("log");
    }

    private boolean executeDig(String target) {
        int depth = 3;
        try { if (target != null) depth = Integer.parseInt(target); } catch (NumberFormatException ignored) {}

        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();

        for (int i = 0; i < depth; i++) {
            BlockPos digPos = pos.below(i + 1);
            if (!level.getBlockState(digPos).isAir()) {
                level.destroyBlock(digPos, true, player);
            }
        }
        return true;
    }

    // ==================== 放置系统（使用 BlockItem 真实放置） ====================

    /**
     * 放置方块
     * 使用 MC 的 BlockItem.place() → 触发放置事件、播放音效、正确处理方块状态
     */
    private boolean executePlace(String target) {
        if (target == null) return true;

        String[] parts = target.split("\\s+", 2);
        String blockName = parts[0];

        // 在背包里找到对应的方块物品
        ItemStack placeStack = findItemInInventory(blockName);
        if (placeStack.isEmpty()) {
            sendWhisper("背包里没有 " + blockName);
            return true;
        }

        // 确定放置位置：面前一格
        Direction facing = player.getDirection();
        BlockPos placePos = player.blockPosition().relative(facing);

        // 如果面前是空气，放面前；否则放脚下前面
        ServerLevel level = player.serverLevel();
        if (!level.getBlockState(placePos).isAir()) {
            placePos = placePos.above();
            if (!level.getBlockState(placePos).isAir()) {
                sendWhisper("这里放不了东西");
                return true;
            }
        }

        // 使用 MC 的 BlockItem 放置机制
        if (placeStack.getItem() instanceof BlockItem blockItem) {
            // 模拟玩家右键放置
            BlockHitResult hitResult = new BlockHitResult(
                Vec3.atCenterOf(placePos.below()),  // 点击位置（放上面）
                Direction.UP,                         // 点击面
                placePos.below(),                     // 方块坐标
                false                                 // 是否在方块内部
            );

            // 使用 useOn() 而非 place()，useOn 内部会正确创建 BlockPlaceContext
            UseOnContext useOnContext = new UseOnContext(
                player, InteractionHand.MAIN_HAND, hitResult
            );
            InteractionResult result = blockItem.useOn(useOnContext);

            if (result.consumesAction()) {
                sendWhisper("放了个 " + blockItem.getBlock().getName().getString());
                memory.addThought(player.tickCount, "放了个 " + blockName);
                return true;
            }
        }

        // 备用方案：直接 setBlock（如果 BlockItem.place 不可用）
        if (placeStack.getItem() instanceof BlockItem blockItem) {
            BlockState placeState = blockItem.getBlock().defaultBlockState();
            level.setBlock(placePos, placeState, 3);
            placeStack.shrink(1);

            // 播放放置音效
            SoundType sound = placeState.getSoundType();
            level.playSound(null, placePos, sound.getPlaceSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);

            sendWhisper("放了个 " + blockItem.getBlock().getName().getString());
            memory.addThought(player.tickCount, "放了个 " + blockName);
            return true;
        }

        sendWhisper(blockName + " 不是方块物品");
        return true;
    }

    // ==================== 攻击系统（走近再打） ====================

    /**
     * 攻击实体
     * 先走到攻击范围（3格），再调用 player.attack()
     */
    private boolean executeAttack(String target) {
        if (target == null) return true;

        ServerLevel level = player.serverLevel();

        // 如果有追踪目标且还活着且在范围内，继续打
        if (attackTarget != null && attackTarget.isAlive() && player.distanceTo(attackTarget) < 5) {
            if (attackCooldown <= 0) {
                // 自动换武器
                autoSelectWeapon();
                player.attack(attackTarget);
                attackCooldown = 20; // 1 秒冷却（MC 攻击冷却）
                memory.addThought(player.tickCount, "继续攻击 " + attackTarget.getName().getString());
            }
            return false; // 还在打
        }

        // 清除旧目标
        attackTarget = null;

        // 搜索新目标
        AABB box = player.getBoundingBox().inflate(15);
        Entity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : level.getEntities(player, box)) {
            // 只攻击怪物，不攻击玩家和友好生物
            if (!(entity instanceof net.minecraft.world.entity.monster.Monster)) continue;

            String entityName = entity.getName().getString().toLowerCase();
            String typeId = entity.getType().toShortString().toLowerCase();

            if (entityName.contains(target.toLowerCase()) ||
                typeId.contains(target.toLowerCase())) {
                double dist = player.distanceTo(entity);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = entity;
                }
            }
        }

        if (closest == null) {
            memory.addThought(player.tickCount, "附近没有 " + target);
            return true;
        }

        // 距离 > 3 格，先走近
        if (closestDist > 3) {
            Vec3 dir = closest.position().subtract(player.position()).normalize();
            if (!walkStep(dir)) {
                stepUp(dir);
            }
            return false; // 还没到攻击范围
        }

        // 自动换武器
        autoSelectWeapon();

        // 在攻击范围内，打！
        attackTarget = closest;
        player.attack(closest);
        attackCooldown = 20;
        memory.addThought(player.tickCount, "攻击了 " + closest.getName().getString());
        return false; // 返回 false 持续追踪
    }

    /**
     * 自动切换到背包里最好的武器
     */
    private void autoSelectWeapon() {
        var inv = player.getInventory();
        int bestSlot = -1;
        float bestDamage = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            String name = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(stack.getItem()).getPath();

            float damage = 1; // 空手
            if (name.contains("netherite_sword")) damage = 4;
            else if (name.contains("diamond_sword")) damage = 3;
            else if (name.contains("iron_sword")) damage = 3;
            else if (name.contains("stone_sword")) damage = 2;
            else if (name.contains("wooden_sword")) damage = 1.5f;
            else if (name.contains("sword")) damage = 2;
            else if (name.contains("axe")) damage = 2; // 斧子也能打

            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0 && bestSlot != inv.selected) {
            inv.selected = bestSlot;
        }
    }

    // ==================== 合成系统（消耗材料，产出物品） ====================

    /**
     * 合成物品
     * 使用 MC 的配方系统：RecipeManager 查配方 → 检查材料 → 消耗材料 → 给予成品
     */
    private boolean executeCraft(String target) {
        if (target == null) return true;

        ServerLevel level = player.serverLevel();
        RecipeManager recipeManager = level.getRecipeManager();
        var registryAccess = level.registryAccess();
        String targetLower = target.toLowerCase().replace(" ", "_");

        // 搜索配方（多种匹配方式）
        CraftingRecipe foundRecipe = null;
        for (var recipe : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = recipe.getResultItem(registryAccess);

            // 注册名：minecraft:oak_planks
            String regName = "";
            try {
                regName = ForgeRegistries.ITEMS.getKey(result.getItem()).toString().toLowerCase();
            } catch (Exception ignored) {}
            String pathName = regName.contains(":") ? regName.split(":")[1] : regName;

            // 显示名：橡木木板
            String displayName = result.getHoverName().getString().toLowerCase();

            // 匹配
            if (pathName.contains(targetLower) || displayName.contains(targetLower)
                || matchItemAlias(targetLower, pathName)) {
                foundRecipe = recipe;
                break;
            }
        }

        if (foundRecipe == null) {
            sendWhisper("我不知道怎么造 " + target);
            memory.addThought(player.tickCount, "找不到 " + target + " 的配方");
            return true;
        }

        // 检查是否需要 3x3 合成台
        boolean needsTable = false;
        if (foundRecipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shapedRecipe) {
            // 如果配方宽度或高度 > 2，需要合成台
            int width = shapedRecipe.getWidth();
            int height = shapedRecipe.getHeight();
            if (width > 2 || height > 2) {
                needsTable = true;
            }
        }

        // 检查并消耗材料
        var inventory = player.getInventory();
        var ingredients = foundRecipe.getIngredients();
        ItemStack resultItem = foundRecipe.getResultItem(registryAccess);

        // 第一遍：检查材料是否足够
        Map<Integer, Integer> slotCounts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            slotCounts.put(i, inventory.getItem(i).getCount());
        }

        for (var ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            boolean found = false;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && ingredient.test(stack) && slotCounts.getOrDefault(i, 0) > 0) {
                    slotCounts.merge(i, -1, Integer::sum);
                    found = true;
                    break;
                }
            }
            if (!found) {
                sendWhisper("材料不够，造不了 " + resultItem.getHoverName().getString());
                memory.addThought(player.tickCount, "想合成 " + target + " 但材料不够");
                return true;
            }
        }

        // 第二遍：实际消耗材料
        for (var ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    stack.shrink(1);
                    break;
                }
            }
        }

        // 给予成品
        ItemStack resultCopy = resultItem.copy();
        if (!inventory.add(resultCopy)) {
            player.drop(resultCopy, false);
        }

        String msg = "合成了 " + resultItem.getHoverName().getString() + " x" + resultItem.getCount();
        if (needsTable) msg += "（需要工作台）";
        sendWhisper(msg);
        memory.addThought(player.tickCount, msg);
        return true;
    }

    /**
     * 物品别名匹配
     */
    private boolean matchItemAlias(String target, String pathName) {
        if ((target.equals("plank") || target.equals("木板")) && pathName.contains("planks")) return true;
        if ((target.equals("stick") || target.equals("木棍")) && pathName.equals("stick")) return true;
        if ((target.equals("torch") || target.equals("火把")) && pathName.equals("torch")) return true;
        if ((target.equals("chest") || target.equals("箱子")) && pathName.equals("chest")) return true;
        if ((target.equals("table") || target.equals("工作台") || target.equals("crafting_table"))
            && pathName.equals("crafting_table")) return true;
        if ((target.equals("furnace") || target.equals("熔炉")) && pathName.equals("furnace")) return true;
        if ((target.equals("pickaxe") || target.equals("镐"))
            && pathName.contains("pickaxe")) return true;
        if ((target.equals("sword") || target.equals("剑"))
            && pathName.contains("sword")) return true;
        if ((target.equals("axe") || target.equals("斧"))
            && pathName.contains("axe")) return true;
        return false;
    }

    // ==================== 其他动作 ====================

    // ==================== 吃东西系统（持续吃，不中断） ====================

    private boolean executeEat(String target) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.isEdible()) {
                if (target == null ||
                    stack.getHoverName().getString().toLowerCase().contains(target.toLowerCase())) {
                    // 先切到食物槽位，再开始吃
                    inv.selected = i;
                    player.startUsingItem(InteractionHand.MAIN_HAND);
                    eatingTicks = 0;
                    memory.addThought(player.tickCount, "开始吃东西");
                    return false; // 返回 false！让状态机保持在 EXECUTING
                }
            }
        }
        memory.addThought(player.tickCount, "背包里没东西吃...");
        return true;
    }

    /**
     * 每 tick 检查吃东西进度
     */
    private void tickEating() {
        eatingTicks++;
        // 吃完了（MC 默认 32 tick）
        if (!player.isUsingItem() || eatingTicks >= EAT_DURATION) {
            player.stopUsingItem();
            memory.addThought(player.tickCount, "吃完了");
            stateMachine.transition(State.IDLE, "吃完了");
            thinkCooldown = autonomousThinkInterval / 2;
        }
    }

    // ==================== 自动捡物品 ====================

    /**
     * 自动捡附近的掉落物（每 10 tick 检查一次，不要每 tick 都查）
     */
    private void tickPickupItems() {
        if (player.tickCount % 10 != 0) return;

        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox().inflate(3); // 3 格内
        for (Entity entity : level.getEntities(player, box)) {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) {
                // 碰到就捡（MC 的 ItemEntity 会自动处理拾取逻辑）
                double dist = player.distanceTo(entity);
                if (dist < 1.5) {
                    // 足够近，MC 会自动触发拾取
                    continue;
                }
                // 稍远一点，走过去捡
                if (dist < 3 && stateMachine.getCurrentState() == State.IDLE) {
                    Vec3 dir = entity.position().subtract(player.position()).normalize();
                    walkStep(dir);
                }
            }
        }
    }

    // ==================== 死亡处理 ====================

    private void handleDeath() {
        AIAgentMod.LOGGER.info("[{}] 死亡了！", name);
        memory.addThought(player.tickCount, "我死了...");

        // 清除当前动作
        miningTarget = null;
        currentAction = null;
        attackTarget = null;

        // 重生：传送到世界出生点
        ServerLevel level = player.serverLevel();
        BlockPos spawn = level.getSharedSpawnPos();
        player.absMoveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        player.setHealth(20.0f);
        player.getFoodData().setFoodLevel(20);

        stateMachine.transition(State.IDLE, "重生了");
        thinkCooldown = autonomousThinkInterval;
        memory.addThought(player.tickCount, "我重生了，上辈子的事还记得...");
    }

    private boolean executeLook(String target) {
        String perception = buildPerception();
        sendWhisper(perception.replace("\n", "；"));
        memory.addThought(player.tickCount, "观察了周围环境");
        return true;
    }

    private boolean executeInventory() {
        var inv = player.getInventory();
        StringBuilder sb = new StringBuilder("背包: ");
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                sb.append(stack.getHoverName().getString())
                  .append("x").append(stack.getCount()).append(" ");
            }
        }
        sendWhisper(sb.toString());
        return true;
    }

    private boolean executeHealth() {
        String status = String.format("HP: %.1f/%.1f | 饥饿: %d",
            player.getHealth(), player.getMaxHealth(),
            player.getFoodData().getFoodLevel());
        sendWhisper(status);
        return true;
    }

    private boolean executeWait(String target) {
        int ticks = 40;
        try { if (target != null) ticks = Integer.parseInt(target); } catch (NumberFormatException ignored) {}
        return stateMachine.getStateTicks() >= ticks;
    }

    private boolean executeSleep() {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                BlockPos check = pos.offset(dx, 0, dz);
                BlockState state = level.getBlockState(check);
                if (state.is(Blocks.RED_BED) || state.is(Blocks.WHITE_BED) ||
                    state.is(Blocks.BLUE_BED) || state.is(Blocks.GREEN_BED)) {
                    player.teleportTo(check.getX() + 0.5, check.getY(), check.getZ() + 0.5);
                    sendWhisper("躺下了...");
                    if (level.isNight()) {
                        level.setDayTime(0);
                    }
                    return true;
                }
            }
        }
        sendWhisper("附近没有床...");
        return true;
    }

    private boolean executeSay(String target) {
        if (target != null && !target.isEmpty()) {
            sendChat(target);
        }
        return true;
    }

    private boolean executeQuery(String target) {
        if (target != null && !target.isEmpty()) {
            String answer = knowledge.query(target);
            memory.addThought(player.tickCount, "查了一下: " + target + " → " + answer);
            sendWhisper(answer.replace("\n", "；"));
        }
        return true;
    }

    // ==================== 危险检测 ====================

    private boolean checkDanger() {
        if (stateMachine.isInDanger()) return false;

        if (player.getHealth() < 6) {
            stateMachine.transition(State.DANGER, "血量过低");
            memory.addThought(player.tickCount, "我快死了！血量只有 " + player.getHealth());
            return true;
        }

        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox().inflate(8);
        for (Entity entity : level.getEntities(player, box)) {
            if (entity instanceof Monster monster) {
                if (player.distanceTo(monster) < 6) {
                    stateMachine.transition(State.DANGER, "附近有 " + monster.getName().getString());
                    memory.addThought(player.tickCount, "危险！附近有 " + monster.getName().getString());
                    return true;
                }
            }
        }

        BlockPos pos = player.blockPosition();
        ServerLevel sl = player.serverLevel();
        BlockState belowFeet = sl.getBlockState(pos);
        BlockState atFeet = sl.getBlockState(pos.above());
        if (belowFeet.is(Blocks.LAVA) || belowFeet.is(Blocks.FIRE) ||
            atFeet.is(Blocks.LAVA) || atFeet.is(Blocks.FIRE)) {
            stateMachine.transition(State.DANGER, "在火/岩浆里");
            return true;
        }

        return false;
    }

    /**
     * 逃跑（真实行走，不瞬移）
     */
    private void fleeFromDanger() {
        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox().inflate(8);

        Entity threat = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : level.getEntities(player, box)) {
            if (entity instanceof Monster) {
                double dist = player.distanceTo(entity);
                if (dist < closestDist) {
                    closestDist = dist;
                    threat = entity;
                }
            }
        }

        if (threat != null) {
            // 反方向跑（用 RUN_SPEED）
            double dx = player.getX() - threat.getX();
            double dz = player.getZ() - threat.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0) {
                Vec3 dir = new Vec3(dx / len, 0, dz / len);
                // 跑两步
                for (int i = 0; i < 2; i++) {
                    walkStep(dir);
                }
            }
        } else {
            walkRandom();
        }
    }

    private boolean isSafe() {
        if (player.getHealth() < 6) return false;

        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox().inflate(8);
        for (Entity entity : level.getEntities(player, box)) {
            if (entity instanceof Monster) {
                if (player.distanceTo(entity) < 8) return false;
            }
        }
        return true;
    }

    // ==================== 感知 ====================

    private String buildPerception() {
        StringBuilder sb = new StringBuilder();

        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();

        sb.append(String.format("位置: %s | 维度: %s\n",
            pos.toShortString(), level.dimension().location()));
        sb.append(String.format("生命: %.1f/%.1f | 饥饿: %d | 时间: %s\n",
            player.getHealth(), player.getMaxHealth(),
            player.getFoodData().getFoodLevel(),
            level.isDay() ? "白天" : "夜晚"));

        BlockState feetBlock = level.getBlockState(pos.below());
        sb.append("脚下: ").append(feetBlock.getBlock().getName().getString()).append("\n");

        AABB box = player.getBoundingBox().inflate(15);
        List<Entity> nearby = level.getEntities(player, box);
        if (!nearby.isEmpty()) {
            sb.append("附近:\n");
            int count = 0;
            for (Entity entity : nearby) {
                if (count >= 8) {
                    sb.append("  ...还有 ").append(nearby.size() - count).append(" 个\n");
                    break;
                }
                double dist = player.distanceTo(entity);
                sb.append(String.format("  - %s (%.0f格)\n",
                    entity.getName().getString(), dist));
                count++;
            }
        }

        var inv = player.getInventory();
        int itemCount = 0;
        StringBuilder invSb = new StringBuilder();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                itemCount += stack.getCount();
                if (invSb.length() < 100) {
                    invSb.append(stack.getHoverName().getString())
                         .append("x").append(stack.getCount()).append(" ");
                }
            }
        }
        sb.append(String.format("背包: %d 件物品 [%s]\n", itemCount, invSb.toString().trim()));

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 自动切换到背包里最适合当前方块的工具
     */
    private void autoSelectTool(BlockState targetBlock) {
        var inv = player.getInventory();
        String blockName = net.minecraftforge.registries.ForgeRegistries.BLOCKS
            .getKey(targetBlock.getBlock()).getPath();

        // 判断需要什么工具
        String neededTool = null;
        if (blockName.contains("ore") || blockName.contains("stone") || blockName.contains("cobble")
            || blockName.contains("deepslate")) {
            neededTool = "pickaxe";
        } else if (blockName.contains("log") || blockName.contains("wood") || blockName.contains("plank")) {
            neededTool = "axe";
        } else if (blockName.contains("dirt") || blockName.contains("grass") || blockName.contains("sand")
            || blockName.contains("gravel") || blockName.contains("snow")) {
            neededTool = "shovel";
        }

        if (neededTool == null) return;

        // 在背包里找对应工具（优先高耐久）
        int bestSlot = -1;
        float bestSpeed = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String itemName = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(stack.getItem()).getPath();
            if (itemName.contains(neededTool)) {
                float speed = stack.getDestroySpeed(targetBlock);
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot >= 0 && bestSlot != inv.selected) {
            inv.selected = bestSlot;
            AIAgentMod.LOGGER.info("[{}] 切换工具到: {}", name, inv.getItem(bestSlot).getHoverName().getString());
        }
    }

    /**
     * 在背包里找物品
     */
    private ItemStack findItemInInventory(String name) {
        var inv = player.getInventory();
        String lower = name.toLowerCase();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getHoverName().getString().toLowerCase();
                // 也检查注册名
                String regName = "";
                try {
                    regName = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getKey(stack.getItem()).toString().toLowerCase();
                } catch (Exception ignored) {}

                if (itemName.contains(lower) || regName.contains(lower)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 在附近搜索方块
     * 同时匹配：显示名（中文）、注册名（英文）、标签名
     */
    private BlockPos findNearbyBlock(String target, int range) {
        ServerLevel level = player.serverLevel();
        BlockPos playerPos = player.blockPosition();
        String lower = target.toLowerCase().replace(" ", "_");

        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        // 球形搜索
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range / 2; dy <= range / 2; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    if (dx * dx + dy * dy + dz * dz > range * range) continue;

                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);

                    if (state.isAir()) continue;

                    // 注册名：minecraft:oak_log
                    String regName = "";
                    try {
                        regName = level.registryAccess()
                            .registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)
                            .getKey(state.getBlock()).toString().toLowerCase();
                    } catch (Exception ignored) {}

                    // 路径名：oak_log（不含命名空间）
                    String pathName = regName.contains(":") ? regName.split(":")[1] : regName;

                    // 显示名：橡木原木
                    String displayName = state.getBlock().getName().getString().toLowerCase();

                    // 多种匹配方式
                    boolean matched = false;
                    if (regName.contains(lower) || pathName.contains(lower)) {
                        matched = true;
                    } else if (displayName.contains(lower)) {
                        matched = true;
                    } else {
                        // 常见别名映射
                        matched = matchBlockAlias(lower, pathName, regName);
                    }

                    if (matched) {
                        double dist = playerPos.distSqr(pos);
                        if (dist < closestDist) {
                            closestDist = dist;
                            closest = pos;
                        }
                    }
                }
            }
        }
        return closest;
    }

    /**
     * 方块别名匹配（LLM 可能用各种方式描述方块）
     */
    private boolean matchBlockAlias(String target, String pathName, String regName) {
        // 木头类
        if ((target.equals("log") || target.equals("原木") || target.equals("木头"))
            && (pathName.contains("log") || pathName.contains("wood"))) return true;
        if ((target.equals("plank") || target.equals("木板"))
            && pathName.contains("planks")) return true;
        // 矿石类
        if ((target.equals("coal") || target.equals("煤"))
            && pathName.contains("coal_ore")) return true;
        if ((target.equals("iron") || target.equals("铁"))
            && pathName.contains("iron_ore")) return true;
        if ((target.equals("diamond") || target.equals("钻石"))
            && pathName.contains("diamond_ore")) return true;
        if ((target.equals("gold") || target.equals("金"))
            && pathName.contains("gold_ore")) return true;
        // 石头类
        if ((target.equals("stone") || target.equals("石头"))
            && pathName.equals("stone")) return true;
        if ((target.equals("cobblestone") || target.equals("圆石"))
            && pathName.equals("cobblestone")) return true;
        // 泥土类
        if ((target.equals("dirt") || target.equals("泥土"))
            && pathName.equals("dirt")) return true;
        if ((target.equals("grass") || target.equals("草方块"))
            && pathName.contains("grass_block")) return true;
        return false;
    }

    private boolean isAddressedToMe(String message) {
        String lower = message.toLowerCase();
        String nameLower = name.toLowerCase();
        return lower.contains("@" + nameLower) ||
               lower.startsWith(nameLower) ||
               lower.contains(nameLower + " ") ||
               lower.contains(nameLower + "，") ||
               lower.contains(nameLower + ",");
    }

    private boolean shouldEavesdrop(String message) {
        return ThreadLocalRandom.current().nextInt(10) == 0;
    }

    private String cleanMessage(String message) {
        String cleaned = message.replaceAll("(?i)@" + java.util.regex.Pattern.quote(name), "").trim();
        if (cleaned.toLowerCase().startsWith(name.toLowerCase())) {
            cleaned = cleaned.substring(name.length()).trim();
        }
        cleaned = cleaned.replaceAll("^[，,。.！!？?\\s]+", "");
        return cleaned;
    }

    private void sendChat(String message) {
        ServerLevel level = player.serverLevel();
        if (level.getServer() != null) {
            String formatted = "§e[" + name + "] §f" + message;
            level.getServer().getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal(formatted), false
            );
        }
    }

    private void sendWhisper(String message) {
        ServerLevel level = player.serverLevel();
        if (level.getServer() != null) {
            String formatted = "§7§o" + name + " 自言自语: " + message;
            level.getServer().getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal(formatted), false
            );
        }
    }

    // ==================== 网络广播 ====================

    /**
     * 广播 AI 移动给所有客户端
     * 使用 MC 原生的 Teleport 包（精确位置更新）
     */
    private void broadcastMovement(double oldX, double oldY, double oldZ) {
        if (player.getServer() == null) return;

        var packet = new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(player);
        for (var realPlayer : player.getServer().getPlayerList().getPlayers()) {
            if (realPlayer != player) {
                realPlayer.connection.send(packet);
            }
        }
    }

    // ==================== 生命周期 ====================

    public void cleanup() {
        // 停止挖掘
        miningTarget = null;
        // 停止攻击
        attackTarget = null;
        // 停止吃东西
        if (player.isUsingItem()) player.stopUsingItem();
        // 清除异步状态
        pendingLLMResponse = null;
        waitingForLLM = false;
        pendingChatSender = null;
        memory.addThought(player.tickCount, "我被移除了...希望下次还能回来");
        memory.save();
        AIAgentMod.LOGGER.info("[{}] 已离开世界", name);
    }

    // ==================== Getters ====================

    public ServerPlayer getPlayer() { return player; }
    public String getName() { return name; }
    public State getState() { return stateMachine.getCurrentState(); }
    public BehaviorStateMachine getStateMachine() { return stateMachine; }
    public ConversationManager getConversation() { return conversation; }
    public MemoryManager getMemory() { return memory; }
}

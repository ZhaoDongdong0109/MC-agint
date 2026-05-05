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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 自主核心 v2 — 三层大脑架构
 *
 * 设计思路：
 * 人类玩家 90% 的时间在做"不用想"的事（走路、挖矿、砍树）。
 * 只有遇到新情况、需要决策时，才"动脑子"。
 *
 * 三层优先级：
 *   反射层 > 本能层 > 思考层
 *
 * 执行流程（每 tick）：
 *   1. 反射层检测危险 → 有危险立即反应，不走后续
 *   2. 本能层执行当前行为 → 有事做就继续，不需要 LLM
 *   3. 本能层空闲 → 决定是否需要调 LLM 思考
 *   4. LLM 返回结果 → 解析为具体动作，交给本能层执行
 *
 * 这样：
 * - 90% 的动作是本地执行，零延迟
 * - LLM 只在需要决策时调用，省 API 费用
 * - AI 行为流畅自然，不会"发呆等 API"
 */
public class AIAutonomousCore {

    private final ServerPlayer player;
    private final String name;

    // 三层大脑
    private final ReflexLayer reflexLayer;
    private final InstinctLayer instinctLayer;

    // 思考层（LLM）
    private final ConversationManager conversation;
    private final MemoryManager memory;
    private final AIApiClient apiClient = new AIApiClient();
    private final GameKnowledge knowledge = new GameKnowledge();
    private final BehaviorStateMachine stateMachine = new BehaviorStateMachine();

    // 异步 LLM
    private volatile String pendingLLMResponse = null;
    private volatile boolean waitingForLLM = false;
    private String pendingChatSender = null;

    // 思考频率控制
    private int thinkCooldown = 0;
    private static final int THINK_COOLDOWN_IDLE = 40;      // 2 秒（空闲时）
    private static final int THINK_COOLDOWN_BUSY = 120;      // 6 秒（有目标时，不需要频繁思考）
    private static final int THINK_COOLDOWN_EXPLORING = 80;  // 4 秒（探索时）

    // 当前目标
    private String currentGoal = null;

    // 服务器主线程执行器
    private java.util.concurrent.Executor serverExecutor;

    public AIAutonomousCore(ServerPlayer player, String name) {
        this.player = player;
        this.name = name;

        // 初始化三层大脑
        this.reflexLayer = new ReflexLayer(player, name);
        this.instinctLayer = new InstinctLayer(player, name);

        this.conversation = new ConversationManager(name);
        this.memory = new MemoryManager(name, apiClient);

        memory.load();

        String personality = conversation.generatePersonality();
        AIAgentMod.LOGGER.info("[{}] 诞生了！性格: {}", name, personality);

        memory.addThought(0, "我刚刚来到这个世界。我的性格是：" + personality);
        memory.save();

        knowledge.setServer(player.getServer());

        this.serverExecutor = command -> {
            if (player.getServer() != null) {
                player.getServer().execute(command);
            }
        };

        stateMachine.transition(State.IDLE, "刚刚召唤");
    }

    // ==================== 核心循环 ====================

    /**
     * 每 tick 调用 — AI 的心跳
     */
    public void tick() {
        stateMachine.tick();
        memory.tick();

        // 检查异步 LLM 结果
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

        // ========= 第一层：反射（最高优先级）=========
        if (reflexLayer.tick()) {
            // 反射层接管了，本能和思考都不执行
            instinctLayer.forceIdle(); // 中断当前行为
            return;
        }

        // ========= 第二层：本能（日常行为）=========
        if (instinctLayer.tick()) {
            // 本能层在处理事情，不需要调 LLM
            return;
        }

        // ========= 第三层：思考（需要决策时）=========
        if (thinkCooldown > 0) {
            thinkCooldown--;
            return;
        }

        // 本能层空闲，决定下一步
        if (!waitingForLLM) {
            decideNextAction();
        }
    }

    // ==================== 思考层决策 ====================

    /**
     * 决定下一步 — 这是唯一调 LLM 的地方
     */
    private void decideNextAction() {
        // 先做本地快速决策（不需要 LLM 的情况）
        if (quickDecision()) {
            return;
        }

        // 需要 LLM 思考
        stateMachine.transition(State.PLANNING, "思考下一步");

        String perception = buildPerception();
        String memoryContext = memory.getRelevantContext(perception);
        String prompt = conversation.buildAutonomousPrompt(
                perception, memoryContext, knowledge, player, currentGoal);

        waitingForLLM = true;
        pendingChatSender = null;
        String systemMsg = conversation.buildSystemMessage();

        apiClient.chatAsync(prompt, systemMsg, 800).thenAcceptAsync(response -> {
            pendingLLMResponse = response;
        }, serverExecutor).exceptionallyAsync(e -> {
            AIAgentMod.LOGGER.warn("[{}] LLM 调用失败: {}", name, e.getMessage());
            pendingLLMResponse = null;
            waitingForLLM = false;
            stateMachine.transition(State.IDLE, "思考失败");
            thinkCooldown = THINK_COOLDOWN_IDLE * 2;
            return null;
        }, serverExecutor);

        thinkCooldown = THINK_COOLDOWN_IDLE;
    }

    /**
     * 本地快速决策 — 不需要 LLM 的常见情况
     * @return true 表示已经做了决策，不需要调 LLM
     */
    private boolean quickDecision() {
        // 1. 天黑了 → 找庇护所或睡觉
        if (isNightTime() && !instinctLayer.isBusy()) {
            if (hasBed()) {
                // 有床就睡觉（这个需要 LLM 决定吗？不需要。）
                instinctLayer.startSeekingShelter();
                thinkCooldown = THINK_COOLDOWN_BUSY;
                return true;
            }
        }

        // 2. 饿了 → 找吃的
        if (player.getFoodData().getFoodLevel() < 10 && !instinctLayer.isBusy()) {
            instinctLayer.startSeekingFood();
            thinkCooldown = THINK_COOLDOWN_BUSY;
            return true;
        }

        // 3. 附近有掉落物 → 去捡
        if (!instinctLayer.isBusy()) {
            AABB box = new AABB(player.blockPosition()).inflate(8);
            var items = player.level().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, box);
            if (!items.isEmpty()) {
                // 走向最近的物品（简单移动，不需要 LLM）
                var nearest = items.get(0);
                Vec3 dir = nearest.position().subtract(player.position()).normalize();
                player.move(MoverType.SELF, new Vec3(dir.x * 0.15, 0, dir.z * 0.15));
                thinkCooldown = 10; // 0.5 秒后再检查
                return true;
            }
        }

        // 4. 有未完成的目标 → 继续做
        if (currentGoal != null && !instinctLayer.isBusy()) {
            // 目标存在但本能层没在做，需要 LLM 来规划怎么继续
            return false;
        }

        return false;
    }

    // ==================== LLM 响应处理 ====================

    private void handleLLMResponse(String rawResponse, String chatSender) {
        if (chatSender == null) {
            handleAutonomousResponse(rawResponse);
        } else {
            handleChatResponse(rawResponse, chatSender);
        }
    }

    private void handleAutonomousResponse(String rawResponse) {
        ParsedResponse parsed = ResponseParser.parse(rawResponse);

        AIAgentMod.LOGGER.info("[{}] 思考: reply={}, action={}, target={}",
                name,
                parsed.reply() != null ? parsed.reply().substring(0, Math.min(30, parsed.reply().length())) : "null",
                parsed.action(),
                parsed.actionTarget());

        if (parsed.thought() != null && !parsed.thought().isEmpty()) {
            memory.addThought(player.tickCount, parsed.thought());
        }

        // 说话
        if (parsed.hasReply()) {
            String reply = parsed.reply();
            conversation.addTurn("assistant", reply);
            sendWhisper(reply);
        }

        // 执行动作 — 交给本能层
        if (parsed.hasAction()) {
            executeAction(parsed.action(), parsed.actionTarget());
        } else {
            stateMachine.transition(State.IDLE, "想完了");
            thinkCooldown = THINK_COOLDOWN_IDLE;
            if (currentGoal != null) {
                currentGoal = null;
            }
        }
    }

    private void handleChatResponse(String rawResponse, String chatSender) {
        String reply = rawResponse.trim();

        // 去掉引号
        if (reply.startsWith("\"") && reply.endsWith("\"")) {
            reply = reply.substring(1, reply.length() - 1).trim();
        }

        // 去掉名字前缀
        int colonIdx = reply.indexOf("：");
        if (colonIdx < 0) colonIdx = reply.indexOf(":");
        if (colonIdx > 0 && colonIdx < 10) {
            String prefix = reply.substring(0, colonIdx).trim();
            if (prefix.length() <= 6 && !prefix.contains(" ")) {
                reply = reply.substring(colonIdx + 1).trim();
            }
        }

        // 强制截断
        if (reply.length() > 60) {
            int cutPoint = -1;
            for (int i = 59; i >= 20; i--) {
                char c = reply.charAt(i);
                if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                    cutPoint = i + 1;
                    break;
                }
            }
            if (cutPoint > 0) {
                reply = reply.substring(0, cutPoint);
            } else {
                reply = reply.substring(0, 57) + "...";
            }
        }

        if (!reply.isEmpty()) {
            conversation.addTurn("assistant", reply);
            sendChat(reply);
            currentGoal = reply;
            memory.addEpisode(player.tickCount, "我答应了 " + chatSender + ": " + reply);
        }

        stateMachine.transition(State.CHATTING, "聊完了");
        thinkCooldown = THINK_COOLDOWN_IDLE / 2;
    }

    // ==================== 动作执行（映射到本能层）====================

    private void executeAction(String action, String target) {
        AIAgentMod.LOGGER.debug("[{}] 执行动作: {} {}", name, action, target);

        switch (action) {
            case "move" -> {
                // 移动不需要 LLM，直接执行
                moveInDirection(target);
                thinkCooldown = 20; // 1 秒后再思考
            }
            case "goto" -> {
                // 走向目标坐标
                try {
                    String[] parts = target.split("[,\\s]+");
                    int x = Integer.parseInt(parts[0].trim());
                    int z = Integer.parseInt(parts.length > 1 ? parts[1].trim() : parts[0].trim());
                    instinctLayer.startWalking(new BlockPos(x, player.getBlockY(), z));
                    thinkCooldown = THINK_COOLDOWN_BUSY;
                } catch (Exception e) {
                    AIAgentMod.LOGGER.warn("[{}] goto 坐标解析失败: {}", name, target);
                    thinkCooldown = THINK_COOLDOWN_IDLE;
                }
            }
            case "mine" -> {
                // 找最近的指定方块并挖掘
                BlockPos targetBlock = findNearestBlock(target, 16);
                if (targetBlock != null) {
                    instinctLayer.startMining(targetBlock);
                    thinkCooldown = THINK_COOLDOWN_BUSY;
                } else {
                    sendWhisper("找不到" + target);
                    thinkCooldown = THINK_COOLDOWN_IDLE;
                }
            }
            case "chop" -> {
                // 找最近的树并砍伐
                BlockPos tree = findNearestBlock("log", 20);
                if (tree != null) {
                    instinctLayer.startChopping(tree);
                    thinkCooldown = THINK_COOLDOWN_BUSY;
                } else {
                    sendWhisper("附近没树");
                    thinkCooldown = THINK_COOLDOWN_IDLE;
                }
            }
            case "eat" -> {
                if (tryEat()) {
                    instinctLayer.startEating();
                    thinkCooldown = 40; // 吃完再想
                } else {
                    sendWhisper("没吃的了");
                    thinkCooldown = THINK_COOLDOWN_IDLE;
                }
            }
            case "attack" -> {
                // 攻击交给本能层处理
                thinkCooldown = 20;
            }
            case "craft" -> {
                // 合成需要思考层来查配方
                thinkCooldown = THINK_COOLDOWN_IDLE;
            }
            case "follow" -> {
                // 跟随玩家
                thinkCooldown = THINK_COOLDOWN_BUSY;
            }
            case "wait" -> {
                try {
                    int ticks = target != null ? Integer.parseInt(target) : 40;
                    thinkCooldown = ticks;
                } catch (Exception e) {
                    thinkCooldown = 40;
                }
            }
            case "explore" -> {
                instinctLayer.startExploring();
                thinkCooldown = THINK_COOLDOWN_EXPLORING;
            }
            default -> {
                thinkCooldown = THINK_COOLDOWN_IDLE;
            }
        }

        stateMachine.transition(State.EXECUTING, action);
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
        instinctLayer.forceIdle();
        stateMachine.transition(State.CHATTING, "和 " + sender + " 聊天");

        String perception = buildPerception();
        String memoryContext = memory.getRelevantContext(perception);
        String prompt = conversation.buildChatPrompt(
                sender, cleanMessage, perception,
                memoryContext, knowledge, player);

        waitingForLLM = true;
        pendingChatSender = sender;
        String systemMsg = conversation.buildSystemMessage();

        // 聊天用 system message + 低 max_tokens → 快速简短回复
        apiClient.chatAsync(prompt, systemMsg, 150).thenAcceptAsync(response -> {
            pendingLLMResponse = response;
        }, serverExecutor).exceptionallyAsync(e -> {
            AIAgentMod.LOGGER.warn("[{}] 聊天 LLM 调用失败: {}", name, e.getMessage());
            sendChat("嗯...我刚走神了，你说啥？");
            waitingForLLM = false;
            pendingLLMResponse = null;
            return null;
        }, serverExecutor);

        memory.addThought(player.tickCount, sender + " 跟我说了: " + cleanMessage);
        memory.addEpisode(player.tickCount, sender + " 对我说: " + cleanMessage);
    }

    // ==================== 工具方法 ====================

    private String buildPerception() {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("生命=%.0f/20 饥饿=%d/20",
                player.getHealth(), player.getFoodData().getFoodLevel()));

        sb.append(String.format(" 位置=(%d,%d,%d)",
                player.getBlockX(), player.getBlockY(), player.getBlockZ()));

        String dim = player.level().dimension().location().getPath();
        if (!dim.equals("overworld")) {
            sb.append(" 维度=").append(dim);
        }

        var mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            sb.append(" 手持=").append(mainHand.getHoverName().getString());
        }

        // 本能层状态
        if (instinctLayer.isBusy()) {
            sb.append(" 正在=").append(instinctLayer.getStatusDescription());
        }

        return sb.toString();
    }

    private void moveInDirection(String direction) {
        double dx = 0, dz = 0;
        double speed = 0.2;
        if (direction == null) direction = "forward";

        switch (direction.toLowerCase()) {
            case "north", "n" -> dz = -speed;
            case "south", "s" -> dz = speed;
            case "east", "e" -> dx = speed;
            case "west", "w" -> dx = -speed;
            case "forward", "f" -> {
                float yaw = player.getYRot();
                dx = -Math.sin(Math.toRadians(yaw)) * speed;
                dz = Math.cos(Math.toRadians(yaw)) * speed;
            }
            default -> {
                dx = (ThreadLocalRandom.current().nextDouble() - 0.5) * speed;
                dz = (ThreadLocalRandom.current().nextDouble() - 0.5) * speed;
            }
        }
        player.move(MoverType.SELF, new Vec3(dx, 0, dz));
    }

    private BlockPos findNearestBlock(String blockName, int range) {
        BlockPos pos = player.blockPosition();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos check = pos.offset(x, y, z);
                    String stateName = player.level().getBlockState(check).getBlock()
                            .getName().getString().toLowerCase();
                    String regName = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                            .getKey(player.level().getBlockState(check).getBlock()).getPath();

                    if (regName.contains(blockName.toLowerCase()) ||
                            stateName.contains(blockName.toLowerCase())) {
                        double dist = pos.distSqr(check);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = check;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    private boolean tryEat() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem().getFoodProperties() != null) {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    private boolean isNightTime() {
        long time = player.level().getDayTime() % 24000;
        return time > 13000 && time < 23000;
    }

    private boolean hasBed() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem().toString().contains("bed")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAddressedToMe(String message) {
        String lower = message.toLowerCase();
        return lower.contains(name.toLowerCase())
                || lower.contains("@" + name.toLowerCase())
                || lower.contains("嘿") || lower.contains("喂");
    }

    private boolean shouldEavesdrop(String message) {
        return message.length() > 5;
    }

    private String cleanMessage(String message) {
        return message.replaceAll("@" + name, "").trim();
    }

    private void sendChat(String message) {
        player.getServer().execute(() -> {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§e[" + name + "]§r " + message));
        });
    }

    private void sendWhisper(String message) {
        sendChat(message);
    }

    private void handleDeath() {
        memory.addThought(player.tickCount, "我死了...好疼");
        stateMachine.transition(State.IDLE, "重生");
        instinctLayer.forceIdle();
        thinkCooldown = 60;
    }

    // ==================== 公开接口（AIPlayerManager 依赖）====================

    /**
     * 获取 AI 玩家实体
     */
    public ServerPlayer getPlayer() {
        return player;
    }

    /**
     * 清理资源（移除 AI 时调用）
     */
    public void cleanup() {
        instinctLayer.forceIdle();
        memory.save();
        conversation.clear();
    }

    /**
     * 清除当前目标，让 AI 自由活动
     */
    public void clearGoal() {
        this.currentGoal = null;
        instinctLayer.forceIdle();
        stateMachine.transition(State.IDLE, "目标被清除");
        thinkCooldown = 20;
    }

    /**
     * 获取当前状态（用于 /ai status 和 /ai list）
     */
    public BehaviorStateMachine.State getState() {
        return stateMachine.getCurrentState();
    }
}

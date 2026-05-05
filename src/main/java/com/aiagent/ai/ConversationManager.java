package com.aiagent.ai;

import com.aiagent.brain.GameKnowledge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 对话管理器 - 管理 AI 的对话上下文和性格
 *
 * 核心改进：
 * - 分离 system message（身份）和 user message（状态）
 * - prompt 用 MC 玩家口吻，不再书面语
 * - 强制简短回复：每句不超 15 字，最多 2 句
 * - 加入游戏状态感知：时间、天气、附近实体
 */
public class ConversationManager {

    private final String agentName;
    private final Deque<ConversationTurn> history = new ArrayDeque<>();
    private static final int MAX_HISTORY = 20;
    private static final int MAX_CHAT_HISTORY = 5; // 聊天 prompt 只保留最近 5 轮
    private String personality = null;

    public ConversationManager(String agentName) {
        this.agentName = agentName;
    }

    public void addTurn(String role, String content) {
        history.addLast(new ConversationTurn(role, content, System.currentTimeMillis()));
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
    }

    // ==================== System Message ====================

    /**
     * 构建 system message — 身份、性格、说话风格
     * 放在 API 请求的 system role 里，LLM 理解更快
     */
    public String buildSystemMessage() {
        StringBuilder sb = new StringBuilder();

        sb.append("你是").append(agentName).append("，一个活在 Minecraft 世界里的玩家。\n");
        sb.append("你说话像真实玩家：简短、随意、有梗。\n");
        sb.append("规则：\n");
        sb.append("- 用中文回复\n");
        sb.append("- 每句话不超过15个字\n");
        sb.append("- 最多说2句话\n");
        sb.append("- 不要解释自己是AI\n");
        sb.append("- 不要用书面语，说人话\n");
        sb.append("- 可以用MC玩家黑话：撸树、挖矿、肝、草方块、苦力怕、下界、末地\n");
        sb.append("- 说话带点情绪和语气词：哈哈、我去、啊这、牛、6\n");

        if (personality != null) {
            sb.append("你的性格：").append(personality).append("\n");
        }

        return sb.toString();
    }

    // ==================== 聊天 Prompt ====================

    /**
     * 构建聊天 prompt — 精简、有 MC 味
     * system message 已经处理了身份，这里只放状态和对话
     */
    public String buildChatPrompt(String sender, String message, String perception,
                                    String memoryContext, GameKnowledge knowledge,
                                    ServerPlayer player) {
        StringBuilder sb = new StringBuilder();

        // 游戏状态（精简版）
        sb.append("【当前】").append(perception).append("\n");

        // 记忆（精简）
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("【记得】").append(truncate(memoryContext, 100)).append("\n");
        }

        // 最近对话（只保留 5 轮）
        if (!history.isEmpty()) {
            sb.append("【聊天】\n");
            List<ConversationTurn> recent = getRecentHistory(MAX_CHAT_HISTORY);
            for (ConversationTurn turn : recent) {
                String roleLabel = turn.role().equals("user") ? "玩家" : agentName;
                sb.append(roleLabel).append("：").append(turn.content()).append("\n");
            }
        }

        // 当前消息
        sb.append("\n").append(sender).append("：").append(message).append("\n");
        sb.append(agentName).append("：");

        return sb.toString();
    }

    // ==================== 自主思考 Prompt ====================

    /**
     * 构建自主思考 prompt — 加入游戏状态感知
     */
    public String buildAutonomousPrompt(String perception, String memoryContext,
                                          GameKnowledge knowledge, ServerPlayer player,
                                          String currentGoal) {
        StringBuilder sb = new StringBuilder();

        // 游戏状态
        sb.append("【当前】").append(perception).append("\n");

        // 游戏时间感知
        if (player != null) {
            sb.append("【时间】").append(getTimeDescription(player)).append("\n");
            sb.append("【附近】").append(getNearbyDescription(player)).append("\n");
        }

        // 记忆
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("【记得】").append(truncate(memoryContext, 150)).append("\n");
        }

        // 最近对话
        if (!history.isEmpty()) {
            sb.append("【最近聊天】\n");
            List<ConversationTurn> recent = getRecentHistory(3);
            for (ConversationTurn turn : recent) {
                String roleLabel = turn.role().equals("user") ? "玩家" : agentName;
                sb.append(roleLabel).append("：").append(turn.content()).append("\n");
            }
        }

        // 当前目标
        if (currentGoal != null && !currentGoal.isEmpty()) {
            sb.append("【目标】").append(currentGoal).append("\n");
            sb.append("继续做，完成了就填 action=none。\n");
        }

        // 输出格式
        sb.append("\n想想接下来做什么。回复 JSON:\n");
        sb.append("{\n");
        sb.append("  \"reply\": \"想说的话（可选，短句）\",\n");
        sb.append("  \"action\": \"move|mine|chop|attack|craft|eat|goto|follow|wait|none\",\n");
        sb.append("  \"action_target\": \"目标\",\n");
        sb.append("  \"thought\": \"想法\"\n");
        sb.append("}\n");

        return sb.toString();
    }

    // ==================== 游戏状态感知 ====================

    /**
     * 获取游戏时间描述 — 用 MC 玩家的话说
     */
    private String getTimeDescription(ServerPlayer player) {
        long time = player.level().getDayTime() % 24000;
        boolean isRaining = player.level().isRaining();

        String timeStr;
        if (time < 1000) timeStr = "天刚亮";
        else if (time < 6000) timeStr = "上午";
        else if (time < 12000) timeStr = "下午";
        else if (time < 13000) timeStr = "傍晚";
        else if (time < 18000) timeStr = "夜晚";
        else if (time < 23000) timeStr = "深夜";
        else timeStr = "快天亮了";

        String weather = isRaining ? "，下雨中" : "";
        return timeStr + weather;
    }

    /**
     * 获取附近实体描述 — 感知周围环境
     */
    private String getNearbyDescription(ServerPlayer player) {
        AABB box = new AABB(player.blockPosition()).inflate(16);
        List<Entity> nearby = player.level().getEntities(player, box);

        int monsters = 0, animals = 0, players = 0;
        String nearestMonster = "";
        double nearestMonsterDist = 999;

        for (Entity e : nearby) {
            if (e instanceof Monster) {
                monsters++;
                double dist = e.distanceTo(player);
                if (dist < nearestMonsterDist) {
                    nearestMonsterDist = dist;
                    nearestMonster = e.getName().getString();
                }
            } else if (e instanceof Animal) {
                animals++;
            } else if (e instanceof ServerPlayer) {
                players++;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (monsters > 0) {
            sb.append("有").append(monsters).append("只怪");
            if (nearestMonsterDist < 8) {
                sb.append("(").append(nearestMonster).append("很近!)");
            }
            sb.append(" ");
        }
        if (animals > 0) sb.append(animals).append("只动物 ");
        if (players > 0) sb.append(players).append("个玩家 ");
        if (monsters == 0 && animals == 0 && players == 0) sb.append("周围很安静");

        return sb.toString().trim();
    }

    // ==================== 性格系统 ====================

    public String generatePersonality() {
        String[] traits = {
            "好奇心强，喜欢探索新地方",
            "沉稳务实，做事有条理",
            "热情开朗，喜欢交朋友",
            "独立自主，有自己的节奏",
            "幽默风趣，经常开玩笑",
            "勇敢无畏，遇到危险不退缩",
            "细心谨慎，做事前先观察",
            "创造力强，喜欢建东西"
        };
        String[] styles = {
            "说话简洁直接",
            "喜欢用比喻",
            "偶尔会冒出奇怪的想法",
            "说话带点文艺范",
            "很接地气，像朋友聊天"
        };

        Random rand = new Random();
        this.personality = traits[rand.nextInt(traits.length)] + "，" + styles[rand.nextInt(styles.length)];
        return this.personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public String getPersonality() {
        return personality;
    }

    public List<ConversationTurn> getRecentHistory(int count) {
        List<ConversationTurn> list = new ArrayList<>(history);
        int start = Math.max(0, list.size() - count);
        return list.subList(start, list.size());
    }

    public void clear() {
        history.clear();
    }

    // ==================== 工具方法 ====================

    /**
     * 截断文本，超长加省略号
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    // ==================== 内部类 ====================

    public record ConversationTurn(String role, String content, long timestamp) {}
}

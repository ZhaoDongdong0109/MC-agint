package com.aiagent.ai;

import com.aiagent.brain.GameKnowledge;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * 对话管理器 - 管理 AI 的对话上下文和性格
 *
 * 职责：
 * - 维护对话历史（不是无限追加，有窗口）
 * - 构建有性格的 prompt
 * - 区分"聊天"和"执行动作"
 * - 处理 LLM 返回的各种格式
 */
public class ConversationManager {

    private final String agentName;
    private final Deque<ConversationTurn> history = new ArrayDeque<>();
    private static final int MAX_HISTORY = 20; // 最多保留 20 轮对话
    private String personality = null; // 性格描述，首次对话时生成

    public ConversationManager(String agentName) {
        this.agentName = agentName;
    }

    /**
     * 添加一轮对话
     */
    public void addTurn(String role, String content) {
        history.addLast(new ConversationTurn(role, content, System.currentTimeMillis()));
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
    }

    /**
     * 构建对话 prompt（给聊天用）
     */
    public String buildChatPrompt(String sender, String message, String perception,
                                    String memoryContext, GameKnowledge knowledge,
                                    ServerPlayer player) {
        StringBuilder sb = new StringBuilder();

        // 系统身份
        sb.append("你是 ").append(agentName).append("，一个生活在 Minecraft 世界里的人。\n");
        sb.append("你和这个世界里的其他玩家是平等的。你有自己的想法、性格和生活。\n");
        sb.append("你不是助手，不是工具，你是一个独立的人。\n\n");

        // 性格
        if (personality != null) {
            sb.append("你的性格: ").append(personality).append("\n\n");
        }

        // 游戏知识
        if (knowledge != null && player != null) {
            sb.append(knowledge.getKnowledgeForPrompt(player)).append("\n");
        }

        // 当前状态
        sb.append("【当前状态】\n").append(perception).append("\n");

        // 记忆
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("【你的记忆】\n").append(memoryContext).append("\n");
        }

        // 对话历史
        if (!history.isEmpty()) {
            sb.append("【最近对话】\n");
            for (ConversationTurn turn : history) {
                String roleLabel = turn.role.equals("user") ? "玩家" : agentName;
                sb.append(roleLabel).append(": ").append(turn.content).append("\n");
            }
        }

        // 当前消息
        sb.append("\n").append(sender).append(" 对你说: ").append(message).append("\n");

        // 回复要求
        sb.append("\n请回复。你可以：\n");
        sb.append("1. 纯聊天回复（直接说人话）\n");
        sb.append("2. 边聊边行动（回复 + 做某件事）\n\n");
        sb.append("回复格式（严格 JSON）:\n");
        sb.append("{\n");
        sb.append("  \"reply\": \"你说的话（必须有，可以为空字符串）\",\n");
        sb.append("  \"action\": \"要做的动作（可选）: move|mine|chop|attack|craft|eat|query|goto|follow|wait|none\",\n");
        sb.append("  \"action_target\": \"动作目标（可选）\",\n");
        sb.append("  \"thought\": \"你内心的想法（不会显示给玩家）\"\n");
        sb.append("}\n");
        sb.append("如果只是聊天不想做动作，action 填 \"none\"。\n");

        return sb.toString();
    }

    /**
     * 构建自主思考 prompt（AI 主动思考时用）
     */
    public String buildAutonomousPrompt(String perception, String memoryContext,
                                          GameKnowledge knowledge, ServerPlayer player) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是 ").append(agentName).append("，一个 Minecraft 世界里的人。\n");
        sb.append("你有自己的想法和目标，不是工具。\n\n");

        if (personality != null) {
            sb.append("你的性格: ").append(personality).append("\n\n");
        }

        // 游戏知识
        if (knowledge != null && player != null) {
            sb.append(knowledge.getKnowledgeForPrompt(player)).append("\n");
        }

        // 当前状态
        sb.append("【当前状态】\n").append(perception).append("\n");

        // 记忆
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("【记忆】\n").append(memoryContext).append("\n");
        }

        // 最近对话
        if (!history.isEmpty()) {
            sb.append("【最近对话】\n");
            for (ConversationTurn turn : history) {
                String roleLabel = turn.role.equals("user") ? "玩家" : agentName;
                sb.append(roleLabel).append(": ").append(turn.content).append("\n");
            }
        }

        sb.append("\n想想接下来做什么。回复 JSON:\n");
        sb.append("{\n");
        sb.append("  \"reply\": \"你想说的话（可选，如果想自言自语）\",\n");
        sb.append("  \"action\": \"要做的动作: move|mine|chop|attack|craft|eat|query|goto|follow|wait|none\",\n");
        sb.append("  \"action_target\": \"动作目标\",\n");
        sb.append("  \"thought\": \"你的想法\"\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * 设置/更新性格
     */
    public void setPersonality(String personality) {
        this.personality = personality;
    }

    /**
     * 生成随机性格（首次召唤时）
     */
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

    // ==================== 内部类 ====================

    public record ConversationTurn(String role, String content, long timestamp) {}
}

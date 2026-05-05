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
     * 构建聊天 prompt —— 自然对话，AI 自己决定回复风格和深度
     */
    public String buildChatPrompt(String sender, String message, String perception,
                                    String memoryContext, GameKnowledge knowledge,
                                    ServerPlayer player) {
        StringBuilder sb = new StringBuilder();

        // 系统身份
        sb.append("你是 ").append(agentName).append("，一个生活在 Minecraft 世界里的人。\n");
        sb.append("你有自己的想法、经历和感受。你不是助手，你是朋友。\n\n");

        // 性格
        if (personality != null) {
            sb.append("你的性格：").append(personality).append("\n\n");
        }

        // 游戏知识
        if (knowledge != null && player != null) {
            sb.append(knowledge.getKnowledgeForPrompt(player)).append("\n");
        }

        // 当前状态
        sb.append("【你现在的情况】\n").append(perception).append("\n");

        // 记忆
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("【你记得的事】\n").append(memoryContext).append("\n");
        }

        // 对话历史
        if (!history.isEmpty()) {
            sb.append("【刚才的对话】\n");
            for (ConversationTurn turn : history) {
                String roleLabel = turn.role.equals("user") ? "玩家" : agentName;
                sb.append(roleLabel).append("：").append(turn.content).append("\n");
            }
        }

        // 当前消息
        sb.append("\n").append(sender).append(" 对你说：").append(message).append("\n\n");

        // 回复指引 —— 自然对话，AI 自己判断
        sb.append("像朋友一样回复。根据对方说的话决定你的回复长短和深度——\n");
        sb.append("随口打招呼就简短回应，想聊就多说几句，有感触就分享感受。\n");
        sb.append("只说话，不要加任何格式标记。\n\n");
        sb.append(agentName).append("：");

        return sb.toString();
    }

    /**
     * 构建自主思考 prompt（AI 主动思考时用）
     * @param currentGoal 当前承诺的目标（玩家交代的事），没有则为 null
     */
    public String buildAutonomousPrompt(String perception, String memoryContext,
                                          GameKnowledge knowledge, ServerPlayer player,
                                          String currentGoal) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是 ").append(agentName).append("，一个 Minecraft 世界里的人。\n");
        sb.append("你有自己的想法和目标，不是工具。\n\n");

        if (personality != null) {
            sb.append("你的性格：").append(personality).append("\n\n");
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
                sb.append(roleLabel).append("：").append(turn.content).append("\n");
            }
        }

        // 当前目标（玩家交代的事）
        if (currentGoal != null && !currentGoal.isEmpty()) {
            sb.append("\n【你之前答应要做的事】\n").append(currentGoal).append("\n");
            sb.append("继续做这件事，直到完成。如果已经完成或做不了，action 填 \"none\"。\n");
        }

        sb.append("\n想想接下来做什么。回复 JSON:\n");
        sb.append("{\n");
        sb.append("  \"reply\": \"你想说的话（可选，如果想自言自语）\",\n");
        sb.append("  \"action\": \"要做的动作: move|mine|chop|attack|craft|eat|query|goto|follow|wait|none\",\n");
        sb.append("  \"action_target\": \"动作目标，如 move 填 forward/north/south/east/west, goto 填坐标 x z, follow 填玩家名, mine/chop/attack 填目标名\",\n");
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

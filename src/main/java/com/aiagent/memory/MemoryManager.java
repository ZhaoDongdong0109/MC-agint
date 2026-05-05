package com.aiagent.memory;

import com.aiagent.AIAgentMod;
import com.aiagent.api.AIApiClient;
import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AI 记忆管理 - 三层记忆系统
 *
 * 1. Working（工作记忆）：最近的原始思考，满了就蒸馏
 * 2. Longterm（长期记忆）：蒸馏后的重要事实，永久保存
 * 3. Episodic（情景记忆）：重要事件记录（和谁做了什么）
 *
 * 蒸馏机制：工作记忆满 30 条 → 调 LLM 提炼 → 存入长期记忆 → 清空工作记忆
 * 自动存盘：脏标记 + 定时保存，关服也能存
 */
public class MemoryManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path MEMORY_DIR = Path.of("ai-agent-memory");

    // 工作记忆上限，满了就触发蒸馏
    private static final int WORKING_CAPACITY = 30;
    // 长期记忆上限
    private static final int LONGTERM_CAPACITY = 200;
    // 情景记忆上限
    private static final int EPISODIC_CAPACITY = 100;
    // 每次蒸馏后保留的最近条数（不蒸馏最新的）
    private static final int DISTILL_KEEP_RECENT = 5;
    // 自动保存间隔（tick，1 tick = 50ms，2400 tick = 2 分钟）
    private static final int AUTO_SAVE_INTERVAL = 2400;

    private final String agentName;
    private final AIApiClient apiClient;

    // 三层记忆
    private final List<MemoryEntry> working = new ArrayList<>();
    private final List<MemoryEntry> longterm = new ArrayList<>();
    private final List<MemoryEntry> episodic = new ArrayList<>();

    // 自动存盘
    private boolean dirty = false;
    private int tickCounter = 0;
    private boolean distilling = false;

    public MemoryManager(String agentName, AIApiClient apiClient) {
        this.agentName = agentName;
        this.apiClient = apiClient;
    }

    // ==================== 每 tick 调用 ====================

    /**
     * 由 AIAutonomousCore.tick() 调用
     * 处理自动存盘和异步蒸馏结果
     */
    public void tick() {
        tickCounter++;

        // 定时自动保存
        if (dirty && tickCounter >= AUTO_SAVE_INTERVAL) {
            save();
            dirty = false;
            tickCounter = 0;
        }
    }

    // ==================== 写入 ====================

    /**
     * 添加思考记录 → 工作记忆
     * 满了自动触发蒸馏
     */
    public void addThought(int tick, String content) {
        working.add(new MemoryEntry(tick, content, 3, "thought"));
        dirty = true;

        // 工作记忆满了，触发蒸馏
        if (working.size() >= WORKING_CAPACITY && !distilling) {
            distillAsync();
        }
    }

    /**
     * 记录重要事件 → 情景记忆
     */
    public void addEpisode(int tick, String content) {
        episodic.add(new MemoryEntry(tick, content, 4, "event"));
        if (episodic.size() > EPISODIC_CAPACITY) {
            episodic.remove(0);
        }
        dirty = true;
    }

    /**
     * 直接添加长期记忆（蒸馏结果用）
     */
    public void addLongterm(int tick, String content, int importance, String type) {
        longterm.add(new MemoryEntry(tick, content, importance, type));
        if (longterm.size() > LONGTERM_CAPACITY) {
            // 淘汰最低重要性的
            longterm.sort(Comparator.comparingInt((MemoryEntry e) -> e.importance).reversed());
            while (longterm.size() > LONGTERM_CAPACITY) {
                longterm.remove(longterm.size() - 1);
            }
        }
        dirty = true;
    }

    // ==================== 读取 ====================

    /**
     * 获取给 prompt 用的记忆上下文
     * 组合三层记忆：工作（最近）+ 长期（重要）+ 情景（相关）
     */
    public String getRelevantContext(String perception) {
        StringBuilder sb = new StringBuilder();

        // 1. 工作记忆：最近 5 条原始思考
        if (!working.isEmpty()) {
            sb.append("最近发生的事：\n");
            int start = Math.max(0, working.size() - 5);
            for (int i = start; i < working.size(); i++) {
                sb.append("- ").append(working.get(i).content).append("\n");
            }
        }

        // 2. 长期记忆：按重要性取 top 10
        if (!longterm.isEmpty()) {
            List<MemoryEntry> sorted = new ArrayList<>(longterm);
            sorted.sort(Comparator.comparingInt((MemoryEntry e) -> e.importance).reversed());
            int limit = Math.min(10, sorted.size());
            sb.append("你知道的事：\n");
            for (int i = 0; i < limit; i++) {
                sb.append("- ").append(sorted.get(i).content).append("\n");
            }
        }

        // 3. 情景记忆：最近 5 条事件
        if (!episodic.isEmpty()) {
            sb.append("你经历过的事：\n");
            int start = Math.max(0, episodic.size() - 5);
            for (int i = start; i < episodic.size(); i++) {
                sb.append("- ").append(episodic.get(i).content).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取全部长期记忆（调试用）
     */
    public List<MemoryEntry> getLongtermMemories() {
        return new ArrayList<>(longterm);
    }

    public int getWorkingSize() { return working.size(); }
    public int getLongtermSize() { return longterm.size(); }
    public int getEpisodicSize() { return episodic.size(); }

    // ==================== 蒸馏 ====================

    /**
     * 异步蒸馏：调 LLM 提炼工作记忆 → 存入长期记忆
     */
    private void distillAsync() {
        if (working.isEmpty()) return;

        distilling = true;

        // 取出要蒸馏的条目（保留最近几条不蒸馏）
        int distillEnd = Math.max(0, working.size() - DISTILL_KEEP_RECENT);
        if (distillEnd == 0) {
            distilling = false;
            return;
        }

        List<MemoryEntry> toDistill = new ArrayList<>(working.subList(0, distillEnd));
        List<MemoryEntry> toKeep = new ArrayList<>(working.subList(distillEnd, working.size()));

        // 构建蒸馏 prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 ").append(agentName).append("，一个 Minecraft 世界里的人。\n");
        prompt.append("以下是你最近的经历，请提炼出值得长期记住的事实。\n\n");
        prompt.append("最近的经历：\n");
        for (MemoryEntry entry : toDistill) {
            prompt.append("- ").append(entry.content).append("\n");
        }
        prompt.append("\n请输出 JSON 数组，每条包含：\n");
        prompt.append("- content: 事实内容（一句话）\n");
        prompt.append("- importance: 1-5（5=必须记住，1=可有可无）\n");
        prompt.append("- type: fact（事实）/ preference（偏好）/ knowledge（知识）/ event（重要事件）\n\n");
        prompt.append("只输出 JSON 数组，不要其他内容。示例：\n");
        prompt.append("[\n");
        prompt.append("  {\"content\": \"冬哥喜欢简单直接的方案\", \"importance\": 4, \"type\": \"preference\"},\n");
        prompt.append("  {\"content\": \"附近有钻石矿在 Y=-59\", \"importance\": 3, \"type\": \"knowledge\"}\n");
        prompt.append("]\n");

        // 异步调用
        final int distillStartTick = toDistill.isEmpty() ? 0 : toDistill.get(0).tick;
        apiClient.chatAsync(prompt.toString()).thenAcceptAsync(response -> {
            try {
                parseDistillResult(response, distillStartTick);
                // 蒸馏成功，替换工作记忆
                working.clear();
                working.addAll(toKeep);
                AIAgentMod.LOGGER.info("[{}] 蒸馏完成，长期记忆 {} 条，工作记忆 {} 条",
                        agentName, longterm.size(), working.size());
            } catch (Exception e) {
                AIAgentMod.LOGGER.warn("[{}] 蒸馏结果解析失败: {}", agentName, e.getMessage());
            } finally {
                distilling = false;
                dirty = true;
            }
        }).exceptionallyAsync(e -> {
            AIAgentMod.LOGGER.warn("[{}] 蒸馏 LLM 调用失败: {}", agentName, e.getMessage());
            distilling = false;
            return null;
        });
    }

    /**
     * 解析蒸馏结果
     */
    private void parseDistillResult(String response, int tick) {
        String cleaned = response.trim();

        // 去掉 markdown 包裹
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            int lastTick = cleaned.lastIndexOf("```");
            if (firstNl > 0 && lastTick > firstNl) {
                cleaned = cleaned.substring(firstNl + 1, lastTick).trim();
            }
        }

        // 提取 JSON 数组
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }

        JsonArray array = JsonParser.parseString(cleaned).getAsJsonArray();
        for (JsonElement elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            String content = obj.has("content") ? obj.get("content").getAsString() : null;
            int importance = obj.has("importance") ? obj.get("importance").getAsInt() : 3;
            String type = obj.has("type") ? obj.get("type").getAsString() : "fact";

            if (content != null && !content.isEmpty()) {
                // 去重：如果长期记忆里已有相同内容，跳过
                boolean duplicate = false;
                for (MemoryEntry existing : longterm) {
                    if (existing.content.equals(content)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    addLongterm(tick, content, Math.min(5, Math.max(1, importance)), type);
                }
            }
        }
    }

    // ==================== 持久化 ====================

    /**
     * 保存所有记忆到文件
     */
    public void save() {
        try {
            Files.createDirectories(MEMORY_DIR);

            JsonObject data = new JsonObject();
            data.addProperty("agentName", agentName);
            data.addProperty("version", 2);  // v2 格式

            data.add("working", serializeList(working));
            data.add("longterm", serializeList(longterm));
            data.add("episodic", serializeList(episodic));

            // 兼容旧格式：把 worldKnowledge 和 playerPreferences 也写上
            JsonArray knowledgeArray = new JsonArray();
            JsonArray prefsArray = new JsonArray();
            for (MemoryEntry entry : longterm) {
                if ("knowledge".equals(entry.type)) {
                    knowledgeArray.add(entry.content);
                } else if ("preference".equals(entry.type)) {
                    prefsArray.add(entry.content);
                }
            }
            data.add("worldKnowledge", knowledgeArray);
            data.add("playerPreferences", prefsArray);

            Path file = getMemoryFile();
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            dirty = false;
            AIAgentMod.LOGGER.debug("[Memory] 已保存 {} 的记忆", agentName);
        } catch (Exception e) {
            AIAgentMod.LOGGER.error("[Memory] 保存失败 {}: {}", agentName, e.getMessage());
        }
    }

    /**
     * 加载记忆
     */
    public void load() {
        try {
            Path file = getMemoryFile();
            if (!Files.exists(file)) return;

            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject data = GSON.fromJson(json, JsonObject.class);

            int version = data.has("version") ? data.get("version").getAsInt() : 1;

            if (version >= 2) {
                // v2 格式：三层记忆
                deserializeList(data.getAsJsonArray("working"), working);
                deserializeList(data.getAsJsonArray("longterm"), longterm);
                deserializeList(data.getAsJsonArray("episodic"), episodic);
            } else {
                // v1 格式兼容：把旧的 thoughts 转成 working + longterm
                if (data.has("thoughts")) {
                    for (var entry : data.getAsJsonArray("thoughts")) {
                        JsonObject obj = entry.getAsJsonObject();
                        working.add(new MemoryEntry(
                                obj.get("tick").getAsInt(),
                                obj.get("content").getAsString(),
                                3, "thought"
                        ));
                    }
                }
                // 旧的 worldKnowledge → longterm
                if (data.has("worldKnowledge")) {
                    for (var entry : data.getAsJsonArray("worldKnowledge")) {
                        longterm.add(new MemoryEntry(0, entry.getAsString(), 3, "knowledge"));
                    }
                }
                // 旧的 playerPreferences → longterm
                if (data.has("playerPreferences")) {
                    for (var entry : data.getAsJsonArray("playerPreferences")) {
                        longterm.add(new MemoryEntry(0, entry.getAsString(), 4, "preference"));
                    }
                }
            }

            AIAgentMod.LOGGER.info("[Memory] 加载 {} 的记忆: 工作{}条, 长期{}条, 情景{}条",
                    agentName, working.size(), longterm.size(), episodic.size());
        } catch (Exception e) {
            AIAgentMod.LOGGER.warn("[Memory] 加载失败 {}: {}", agentName, e.getMessage());
        }
    }

    // ==================== 序列化辅助 ====================

    private JsonArray serializeList(List<MemoryEntry> list) {
        JsonArray array = new JsonArray();
        for (MemoryEntry entry : list) {
            JsonObject obj = new JsonObject();
            obj.addProperty("tick", entry.tick);
            obj.addProperty("content", entry.content);
            obj.addProperty("importance", entry.importance);
            obj.addProperty("type", entry.type);
            array.add(obj);
        }
        return array;
    }

    private void deserializeList(JsonArray array, List<MemoryEntry> target) {
        if (array == null) return;
        for (var elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            target.add(new MemoryEntry(
                    obj.has("tick") ? obj.get("tick").getAsInt() : 0,
                    obj.get("content").getAsString(),
                    obj.has("importance") ? obj.get("importance").getAsInt() : 3,
                    obj.has("type") ? obj.get("type").getAsString() : "fact"
            ));
        }
    }

    private Path getMemoryFile() {
        return MEMORY_DIR.resolve(agentName + ".json");
    }

    // ==================== 记忆条目 ====================

    public static class MemoryEntry {
        public final int tick;
        public final String content;
        public final int importance;  // 1-5，5=必须记住
        public final String type;     // thought/fact/preference/knowledge/event

        public MemoryEntry(int tick, String content, int importance, String type) {
            this.tick = tick;
            this.content = content;
            this.importance = importance;
            this.type = type;
        }
    }
}

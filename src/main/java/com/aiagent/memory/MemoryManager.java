package com.aiagent.memory;

import com.aiagent.AIAgentMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 记忆管理 - 本地存储
 */
public class MemoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path MEMORY_DIR = Path.of("ai-agent-memory");

    private final String agentName;
    private final List<ThoughtEntry> thoughts = new ArrayList<>();
    private final List<String> worldKnowledge = new ArrayList<>();
    private final List<String> playerPreferences = new ArrayList<>();

    public MemoryManager(String agentName) {
        this.agentName = agentName;
    }

    /**
     * 加载记忆
     */
    public void load() {
        try {
            Path file = getMemoryFile();
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject data = GSON.fromJson(json, JsonObject.class);

                if (data.has("thoughts")) {
                    for (var entry : data.getAsJsonArray("thoughts")) {
                        JsonObject obj = entry.getAsJsonObject();
                        thoughts.add(new ThoughtEntry(
                                obj.get("tick").getAsInt(),
                                obj.get("content").getAsString()
                        ));
                    }
                }

                if (data.has("worldKnowledge")) {
                    for (var entry : data.getAsJsonArray("worldKnowledge")) {
                        worldKnowledge.add(entry.getAsString());
                    }
                }

                if (data.has("playerPreferences")) {
                    for (var entry : data.getAsJsonArray("playerPreferences")) {
                        playerPreferences.add(entry.getAsString());
                    }
                }

                AIAgentMod.LOGGER.info("[Memory] Loaded {} memories for {}", thoughts.size(), agentName);
            }
        } catch (Exception e) {
            AIAgentMod.LOGGER.warn("[Memory] Failed to load memory for {}: {}", agentName, e.getMessage());
        }
    }

    /**
     * 保存记忆
     */
    public void save() {
        try {
            Files.createDirectories(MEMORY_DIR);
            Path file = getMemoryFile();

            JsonObject data = new JsonObject();
            data.addProperty("agentName", agentName);

            JsonArray thoughtsArray = new JsonArray();
            // 只保存最近 100 条
            int start = Math.max(0, thoughts.size() - 100);
            for (int i = start; i < thoughts.size(); i++) {
                ThoughtEntry entry = thoughts.get(i);
                JsonObject obj = new JsonObject();
                obj.addProperty("tick", entry.tick);
                obj.addProperty("content", entry.content);
                thoughtsArray.add(obj);
            }
            data.add("thoughts", thoughtsArray);

            JsonArray knowledgeArray = new JsonArray();
            for (String k : worldKnowledge) {
                knowledgeArray.add(k);
            }
            data.add("worldKnowledge", knowledgeArray);

            JsonArray prefsArray = new JsonArray();
            for (String p : playerPreferences) {
                prefsArray.add(p);
            }
            data.add("playerPreferences", prefsArray);

            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            AIAgentMod.LOGGER.info("[Memory] Saved memory for {}", agentName);
        } catch (Exception e) {
            AIAgentMod.LOGGER.error("[Memory] Failed to save memory for {}: {}", agentName, e.getMessage());
        }
    }

    /**
     * 添加思考记录
     */
    public void addThought(int tick, String thought) {
        thoughts.add(new ThoughtEntry(tick, thought));
        if (thoughts.size() > 200) {
            thoughts.remove(0);
        }
    }

    /**
     * 添加世界知识
     */
    public void addWorldKnowledge(String knowledge) {
        if (!worldKnowledge.contains(knowledge)) {
            worldKnowledge.add(knowledge);
            if (worldKnowledge.size() > 50) {
                worldKnowledge.remove(0);
            }
        }
    }

    /**
     * 添加玩家偏好
     */
    public void addPlayerPreference(String preference) {
        playerPreferences.add(preference);
    }

    /**
     * 获取与当前感知相关的记忆上下文
     */
    public String getRelevantContext(String perception) {
        StringBuilder sb = new StringBuilder();

        // 最近的思考
        if (!thoughts.isEmpty()) {
            sb.append("最近的思考:\n");
            int start = Math.max(0, thoughts.size() - 5);
            for (int i = start; i < thoughts.size(); i++) {
                sb.append("- ").append(thoughts.get(i).content).append("\n");
            }
        }

        // 世界知识
        if (!worldKnowledge.isEmpty()) {
            sb.append("已知信息:\n");
            for (String k : worldKnowledge) {
                sb.append("- ").append(k).append("\n");
            }
        }

        return sb.toString();
    }

    private Path getMemoryFile() {
        return MEMORY_DIR.resolve(agentName + ".json");
    }

    private record ThoughtEntry(int tick, String content) {}
}

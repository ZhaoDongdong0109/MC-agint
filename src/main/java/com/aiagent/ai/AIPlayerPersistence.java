package com.aiagent.ai;

import com.aiagent.AIAgentMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 玩家状态持久化
 *
 * 负责在关服时保存 AI 的位置、背包、血量等状态，
 * 并在开服时自动恢复。
 *
 * 存储位置：ai-agent-data/<name>.json
 * （与 memory 分开，memory 是 AI 的"想法"，这是 AI 的"身体"）
 */
public class AIPlayerPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_DIR = Path.of("ai-agent-data");

    /**
     * 保存单个 AI 的状态
     */
    public static void save(ServerPlayer player, String name) {
        try {
            Files.createDirectories(DATA_DIR);
            JsonObject data = new JsonObject();

            // 基本信息
            data.addProperty("name", name);
            data.addProperty("uuid", player.getStringUUID());

            // 位置
            JsonObject pos = new JsonObject();
            pos.addProperty("x", player.getX());
            pos.addProperty("y", player.getY());
            pos.addProperty("z", player.getZ());
            pos.addProperty("yaw", player.getYRot());
            pos.addProperty("pitch", player.getXRot());
            data.add("position", pos);

            // 维度
            data.addProperty("dimension", player.level().dimension().location().toString());

            // 生存状态
            data.addProperty("health", player.getHealth());
            data.addProperty("maxHealth", player.getMaxHealth());
            data.addProperty("foodLevel", player.getFoodData().getFoodLevel());
            data.addProperty("saturation", player.getFoodData().getSaturationLevel());

            // 经验
            data.addProperty("experience", player.experienceLevel);

            // 背包
            JsonArray inventory = new JsonArray();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty()) {
                    CompoundTag itemTag = new CompoundTag();
                    stack.save(itemTag);
                    JsonObject slot = new JsonObject();
                    slot.addProperty("slot", i);
                    slot.addProperty("nbt", itemTag.toString());
                    inventory.add(slot);
                }
            }
            data.add("inventory", inventory);

            // 游戏模式
            data.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());

            // 写入文件
            Path file = DATA_DIR.resolve(name + ".json");
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            AIAgentMod.LOGGER.info("[Persistence] 已保存 AI 状态: {}", name);

        } catch (Exception e) {
            AIAgentMod.LOGGER.error("[Persistence] 保存 AI 状态失败 {}: {}", name, e.getMessage());
        }
    }

    /**
     * 加载单个 AI 的状态
     * @return 加载的数据对象，不存在则返回 null
     */
    public static JsonObject load(String name) {
        try {
            Path file = DATA_DIR.resolve(name + ".json");
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject data = GSON.fromJson(json, JsonObject.class);
                AIAgentMod.LOGGER.info("[Persistence] 已加载 AI 状态: {}", name);
                return data;
            }
        } catch (Exception e) {
            AIAgentMod.LOGGER.warn("[Persistence] 加载 AI 状态失败 {}: {}", name, e.getMessage());
        }
        return null;
    }

    /**
     * 将保存的状态恢复到 AI 玩家身上
     */
    public static void restore(ServerPlayer player, JsonObject data) {
        try {
            // 恢复位置
            if (data.has("position")) {
                JsonObject pos = data.getAsJsonObject("position");
                double x = pos.get("x").getAsDouble();
                double y = pos.get("y").getAsDouble();
                double z = pos.get("z").getAsDouble();
                float yaw = pos.has("yaw") ? pos.get("yaw").getAsFloat() : 0;
                float pitch = pos.has("pitch") ? pos.get("pitch").getAsFloat() : 0;
                player.absMoveTo(x, y, z, yaw, pitch);
            }

            // 恢复生存状态
            if (data.has("health")) {
                player.setHealth(data.get("health").getAsFloat());
            }
            if (data.has("foodLevel")) {
                player.getFoodData().setFoodLevel(data.get("foodLevel").getAsInt());
            }
            if (data.has("saturation")) {
                player.getFoodData().setSaturation(data.get("saturation").getAsFloat());
            }
            if (data.has("experience")) {
                player.experienceLevel = data.get("experience").getAsInt();
            }

            // 恢复背包
            if (data.has("inventory")) {
                // 先清空
                player.getInventory().clearContent();
                JsonArray inv = data.getAsJsonArray("inventory");
                for (var entry : inv) {
                    JsonObject slot = entry.getAsJsonObject();
                    int slotIdx = slot.get("slot").getAsInt();
                    String nbtStr = slot.get("nbt").getAsString();
                    try {
                        CompoundTag itemTag = net.minecraft.nbt.TagParser.parseTag(nbtStr);
                        ItemStack stack = ItemStack.of(itemTag);
                        player.getInventory().setItem(slotIdx, stack);
                    } catch (Exception e) {
                        AIAgentMod.LOGGER.warn("[Persistence] 恢复物品失败 slot={}: {}", slotIdx, e.getMessage());
                    }
                }
            }

            // 恢复游戏模式
            if (data.has("gameMode")) {
                String mode = data.get("gameMode").getAsString();
                try {
                    player.setGameMode(net.minecraft.world.level.GameType.byName(mode));
                } catch (Exception ignored) {}
            }

            AIAgentMod.LOGGER.info("[Persistence] 已恢复 AI 状态: {}", data.get("name").getAsString());

        } catch (Exception e) {
            AIAgentMod.LOGGER.error("[Persistence] 恢复 AI 状态失败: {}", e.getMessage());
        }
    }

    /**
     * 获取所有已保存的 AI 名称列表
     */
    public static List<String> listSaved() {
        List<String> names = new ArrayList<>();
        try {
            if (Files.exists(DATA_DIR)) {
                Files.list(DATA_DIR)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        String filename = p.getFileName().toString();
                        names.add(filename.substring(0, filename.length() - 5));
                    });
            }
        } catch (Exception e) {
            AIAgentMod.LOGGER.warn("[Persistence] 列出已保存 AI 失败: {}", e.getMessage());
        }
        return names;
    }

    /**
     * 删除已保存的 AI 状态
     */
    public static void delete(String name) {
        try {
            Path file = DATA_DIR.resolve(name + ".json");
            Files.deleteIfExists(file);
            AIAgentMod.LOGGER.info("[Persistence] 已删除 AI 状态: {}", name);
        } catch (Exception e) {
            AIAgentMod.LOGGER.warn("[Persistence] 删除 AI 状态失败 {}: {}", name, e.getMessage());
        }
    }
}

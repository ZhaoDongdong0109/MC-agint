package com.aiagent.ai;

import com.aiagent.AIAgentMod;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 玩家管理器
 *
 * 使用自定义 AIFakePlayer（而非 Forge FakePlayerFactory），防止关服时
 * FakePlayer 被保存到 playerdata 导致重启卡死。
 *
 * 持久化：关服时保存 AI 状态（位置/背包/血量），开服时自动恢复。
 */
public class AIPlayerManager {
    private static final AIPlayerManager INSTANCE = new AIPlayerManager();
    private final Map<String, AIAutonomousCore> agents = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public static AIPlayerManager getInstance() {
        return INSTANCE;
    }

    /**
     * 设置服务器实例（开服时调用）
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 召唤 AI（新创建或从存档恢复）
     */
    public ServerPlayer spawn(MinecraftServer server, ServerLevel level, String name, ServerPlayer spawner) {
        if (agents.containsKey(name)) {
            return agents.get(name).getPlayer();
        }

        this.server = server;

        // 尝试从存档恢复
        JsonObject savedData = AIPlayerPersistence.load(name);

        // 创建 AI 玩家（使用自定义 FakePlayer，不污染存档）
        UUID uuid = UUID.nameUUIDFromBytes(("aiagent:" + name).getBytes());
        GameProfile profile = new GameProfile(uuid, name);
        ServerPlayer aiPlayer = new AIFakePlayer(level, profile);

        if (savedData != null) {
            // 从存档恢复状态
            AIPlayerPersistence.restore(aiPlayer, savedData);
            AIAgentMod.LOGGER.info("[AI] 从存档恢复: {} 位置: {}", name, aiPlayer.blockPosition().toShortString());
        } else if (spawner != null) {
            // 新召唤：传送到召唤者旁边
            aiPlayer.absMoveTo(spawner.getX() + 2, spawner.getY(), spawner.getZ() + 2);
            AIAgentMod.LOGGER.info("[AI] 新召唤: {}", name);
        } else {
            // 无存档也无召唤者（不应该发生），放在世界出生点
            aiPlayer.absMoveTo(level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getY(), level.getSharedSpawnPos().getZ());
            AIAgentMod.LOGGER.info("[AI] 新召唤（出生点）: {}", name);
        }

        // 设置游戏模式为生存
        aiPlayer.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        // 让其他玩家能看到 AI（广播 PlayerInfo + SpawnPlayer）
        broadcastAIJoin(aiPlayer, server);

        // 创建 AI 核心
        AIAutonomousCore core = new AIAutonomousCore(aiPlayer, name);
        agents.put(name, core);

        AIAgentMod.LOGGER.info("[AI] 已就绪: {} ({})", name, uuid);
        return aiPlayer;
    }

    /**
     * 移除单个 AI
     */
    public boolean remove(String name) {
        AIAutonomousCore core = agents.remove(name);
        if (core != null) {
            ServerPlayer player = core.getPlayer();
            core.cleanup();
            // 广播离开包给所有玩家
            if (player != null && server != null) {
                broadcastAILeave(player, server);
            }
            // 删除存档
            AIPlayerPersistence.delete(name);
            return true;
        }
        return false;
    }

    /**
     * 保存所有 AI 状态（关服前调用）
     */
    public void saveAll() {
        if (agents.isEmpty()) return;
        AIAgentMod.LOGGER.info("[AI] 正在保存 {} 个 AI 状态...", agents.size());
        for (Map.Entry<String, AIAutonomousCore> entry : agents.entrySet()) {
            String name = entry.getKey();
            ServerPlayer player = entry.getValue().getPlayer();
            if (player != null) {
                AIPlayerPersistence.save(player, name);
            }
        }
    }

    /**
     * 清理所有 AI（关服前调用）
     * 保存状态 → 停止 AI → 从 player list 移除
     */
    public void cleanupAll() {
        AIAgentMod.LOGGER.info("[AI] 正在清理 {} 个 AI...", agents.size());

        // 先保存
        saveAll();

        // 再清理 + 广播离开
        for (Map.Entry<String, AIAutonomousCore> entry : agents.entrySet()) {
            AIAutonomousCore core = entry.getValue();
            ServerPlayer player = core.getPlayer();
            core.cleanup();
            if (player != null && server != null) {
                broadcastAILeave(player, server);
            }
        }
        agents.clear();
    }

    /**
     * 自动恢复所有已保存的 AI（开服时调用）
     */
    public void restoreAll(MinecraftServer server) {
        this.server = server;
        java.util.List<String> saved = AIPlayerPersistence.listSaved();
        if (saved.isEmpty()) return;

        ServerLevel overworld = server.overworld();
        AIAgentMod.LOGGER.info("[AI] 正在恢复 {} 个 AI...", saved.size());

        for (String name : saved) {
            try {
                if (!agents.containsKey(name)) {
                    spawn(server, overworld, name, null);
                }
            } catch (Exception e) {
                AIAgentMod.LOGGER.error("[AI] 恢复 {} 失败: {}", name, e.getMessage());
            }
        }
    }

    public void onChat(String sender, String message) {
        for (AIAutonomousCore core : agents.values()) {
            core.onChatReceived(sender, message);
        }
    }

    /**
     * 停止 AI 的当前目标，让它自由活动
     */
    public boolean stopGoal(String name) {
        AIAutonomousCore core = agents.get(name);
        if (core != null) {
            core.clearGoal();
            return true;
        }
        return false;
    }

    public String getStatus(String name) {
        AIAutonomousCore core = agents.get(name);
        if (core != null) {
            return String.format("名称: %s\n状态: %s\n位置: %s\n生命: %.1f",
                    name,
                    core.getState().name(),
                    core.getPlayer().blockPosition().toShortString(),
                    core.getPlayer().getHealth());
        }
        return null;
    }

    public String listAll() {
        if (agents.isEmpty()) {
            return "当前没有活跃的 AI 玩家";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, AIAutonomousCore> entry : agents.entrySet()) {
            AIAutonomousCore core = entry.getValue();
            sb.append(String.format("- %s [%s] HP:%.1f 位置:%s\n",
                    entry.getKey(),
                    core.getState().name(),
                    core.getPlayer().getHealth(),
                    core.getPlayer().blockPosition().toShortString()));
        }
        return sb.toString().trim();
    }

    public void tickAll() {
        for (AIAutonomousCore core : agents.values()) {
            core.tick();
        }
    }

    /**
     * 广播 AI 加入 — 让所有真人玩家能看到 AI
     *
     * 需要发送两个包：
     * 1. ClientboundPlayerInfoUpdatePacket — 注册玩家信息（Tab 列表、皮肤）
     * 2. ClientboundAddPlayerPacket — 生成玩家实体（渲染出来）
     */
    private void broadcastAIJoin(ServerPlayer aiPlayer, MinecraftServer server) {
        var playerList = server.getPlayerList();
        var players = playerList.getPlayers();

        // 1. 发送 PlayerInfo（注册玩家信息到客户端）
        var profile = aiPlayer.getGameProfile();
        var playerInfoPacket = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
            java.util.EnumSet.of(
                net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED
            ),
            java.util.List.of(aiPlayer)
        );

        // 2. 发送 SpawnPlayer（在世界中生成实体）
        var spawnPacket = new net.minecraft.network.protocol.game.ClientboundAddPlayerPacket(aiPlayer);

        for (ServerPlayer realPlayer : players) {
            if (realPlayer == aiPlayer) continue;
            realPlayer.connection.send(playerInfoPacket);
            realPlayer.connection.send(spawnPacket);
        }

        AIAgentMod.LOGGER.info("[AI] 已广播 {} 的加入包给 {} 个玩家", aiPlayer.getName().getString(), players.size());
    }

    /**
     * 广播 AI 离开 — 从客户端移除 AI
     */
    private void broadcastAILeave(ServerPlayer aiPlayer, MinecraftServer server) {
        var playerList = server.getPlayerList();
        var players = playerList.getPlayers();

        // 发送 PlayerInfo 移除
        var removePacket = new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(
            java.util.List.of(aiPlayer.getUUID())
        );

        // 发送实体移除
        var destroyPacket = new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(aiPlayer.getId());

        for (ServerPlayer realPlayer : players) {
            if (realPlayer == aiPlayer) continue;
            realPlayer.connection.send(removePacket);
            realPlayer.connection.send(destroyPacket);
        }

        AIAgentMod.LOGGER.info("[AI] 已广播 {} 的离开包", aiPlayer.getName().getString());
    }
}

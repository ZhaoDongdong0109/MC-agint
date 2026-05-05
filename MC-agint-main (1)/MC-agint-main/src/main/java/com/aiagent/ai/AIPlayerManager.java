package com.aiagent.ai;

import com.aiagent.AIAgentMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 玩家管理器
 *
 * 使用 Forge 的 FakePlayerFactory 创建假玩家，避免 connection 为 null 导致 NPE
 * FakePlayer 内部自带 dummy connection，所有 API 都能安全调用
 */
public class AIPlayerManager {
    private static final AIPlayerManager INSTANCE = new AIPlayerManager();
    private final Map<String, AIAutonomousCore> agents = new ConcurrentHashMap<>();

    public static AIPlayerManager getInstance() {
        return INSTANCE;
    }

    public ServerPlayer spawn(MinecraftServer server, ServerLevel level, String name, ServerPlayer spawner) {
        if (agents.containsKey(name)) {
            return agents.get(name).getPlayer();
        }

        // 使用 Forge 的 FakePlayerFactory（自带 dummy connection，不会 NPE）
        UUID uuid = UUID.nameUUIDFromBytes(("aiagent:" + name).getBytes());
        GameProfile profile = new GameProfile(uuid, name);

        ServerPlayer aiPlayer = FakePlayerFactory.get(level, profile);

        // 传送到召唤者旁边
        aiPlayer.absMoveTo(spawner.getX() + 2, spawner.getY(), spawner.getZ() + 2);

        // 设置游戏模式为生存（FakePlayer 默认可能是 ADVENTURE）
        aiPlayer.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        // FakePlayerFactory 已经处理了 dummy connection，不需要手动加到玩家列表
        // 如果需要让其他玩家看到 AI，可以通过 spawnEntity 或自定义渲染实现

        AIAutonomousCore core = new AIAutonomousCore(aiPlayer, name);
        agents.put(name, core);

        AIAgentMod.LOGGER.info("[AI] 已召唤: {} ({})", name, uuid);
        return aiPlayer;
    }

    public boolean remove(String name) {
        AIAutonomousCore core = agents.remove(name);
        if (core != null) {
            core.cleanup();
            ServerPlayer player = core.getPlayer();
            // 从玩家列表移除
            if (player != null && player.getServer() != null) {
                player.getServer().getPlayerList().remove(player);
            }
            return true;
        }
        return false;
    }

    public void onChat(String sender, String message) {
        for (AIAutonomousCore core : agents.values()) {
            core.onChatReceived(sender, message);
        }
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
}

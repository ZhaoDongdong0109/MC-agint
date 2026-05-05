package com.aiagent;

import com.aiagent.ai.AIPlayerManager;
import com.aiagent.command.AICommand;
import com.aiagent.config.AIConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(AIAgentMod.MOD_ID)
public class AIAgentMod {
    public static final String MOD_ID = "aiagent";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 标记是否已恢复过 AI（防止重复恢复） */
    private boolean restored = false;

    public AIAgentMod(IEventBus modEventBus, ModContainer modContainer) {
        // 注册生命周期事件
        modEventBus.addListener(this::setup);

        // 注册游戏事件（聊天、Tick、命令等）
        NeoForge.EVENT_BUS.register(this);

        // 注册 NeoForge Config
        modContainer.registerConfig(
                net.neoforged.fml.config.ModConfig.Type.COMMON,
                AIConfig.SPEC,
                "aiagent-common.toml"
        );

        AIConfig.init();
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("[AI Agent] Mod loaded. Use /ai spawn <name> to summon your AI companion.");
        cleanupStalePlayerData();
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        AICommand.register(event.getDispatcher());
    }

    /**
     * 监听服务器聊天 - AI 会听到所有聊天内容
     */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        String sender = event.getPlayer().getName().getString();
        String message = event.getMessage().getString();

        AIPlayerManager.getInstance().onChat(sender, message);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            AIPlayerManager.getInstance().tickAll();
        }
    }

    /**
     * 玩家登录时自动恢复 AI（只执行一次）
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (restored) return;
        restored = true;

        event.getEntity().getServer().execute(() -> {
            AIPlayerManager.getInstance().setServer(event.getEntity().getServer());
            AIPlayerManager.getInstance().restoreAll(event.getEntity().getServer());
        });
    }

    /**
     * 服务器关闭前 - 保存所有 AI 状态并清理
     */
    @SubscribeEvent
    public void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        LOGGER.info("[AI] 服务器正在关闭，保存 AI 状态...");
        AIPlayerManager.getInstance().cleanupAll();
    }

    /**
     * 启动时清理残留的 FakePlayer 存档文件
     */
    private void cleanupStalePlayerData() {
        try {
            java.io.File playerDataDir = new java.io.File("world/playerdata");
            if (!playerDataDir.exists()) return;

            java.io.File[] files = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files == null) return;

            for (java.io.File file : files) {
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(file);
                    net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(fis);
                    fis.close();

                    if (tag != null && tag.contains("aiagent") && tag.getBoolean("aiagent")) {
                        if (file.delete()) {
                            LOGGER.info("[AI] 已清理残留的 FakePlayer 存档: {}", file.getName());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AI] 清理残留存档时出错: {}", e.getMessage());
        }
    }
}

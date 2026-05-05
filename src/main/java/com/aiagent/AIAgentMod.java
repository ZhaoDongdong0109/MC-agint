package com.aiagent;

import com.aiagent.ai.AIPlayerManager;
import com.aiagent.command.AICommand;
import com.aiagent.config.AIConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AIAgentMod.MOD_ID)
public class AIAgentMod {
    public static final String MOD_ID = "aiagent";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /** 标记是否已恢复过 AI（防止重复恢复） */
    private boolean restored = false;

    public AIAgentMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);

        // 注册 Forge Config
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                AIConfig.SPEC,
                "aiagent-common.toml"
        );

        AIConfig.init();
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("[AI Agent] Mod loaded. Use /ai spawn <name> to summon your AI companion.");
        // 启动时清理可能残留的 FakePlayer 存档（防崩溃兜底）
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

        // 把聊天转发给所有 AI
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
     * 选择 PlayerEvent.LoggedIn 而非 ServerStartedEvent，因为此时世界已完全加载
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (restored) return;
        restored = true;

        // 延迟一 tick 执行，确保服务器完全就绪
        event.getEntity().getServer().execute(() -> {
            AIPlayerManager.getInstance().setServer(event.getEntity().getServer());
            AIPlayerManager.getInstance().restoreAll(event.getEntity().getServer());
        });
    }

    /**
     * 服务器关闭前 - 保存所有 AI 状态并清理
     * 这是修复"卡 100%"的核心：确保 FakePlayer 不会残留到 playerdata
     */
    @SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        LOGGER.info("[AI] 服务器正在关闭，保存 AI 状态...");
        AIPlayerManager.getInstance().cleanupAll();
    }

    /**
     * 启动时清理残留的 FakePlayer 存档文件
     *
     * 扫描 playerdata/ 下所有 .dat 文件，检查是否是 AI Agent 的 FakePlayer。
     * 这是崩溃场景的兜底——如果游戏没正常关闭，ServerStoppingEvent 没触发，
     * 这里会在下次启动时清理掉那些会导致卡死的文件。
     */
    private void cleanupStalePlayerData() {
        try {
            java.io.File playerDataDir = new java.io.File("world/playerdata");
            if (!playerDataDir.exists()) return;

            java.io.File[] files = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files == null) return;

            for (java.io.File file : files) {
                try {
                    // 尝试读取文件，检查是否包含 aiagent 标记
                    java.io.FileInputStream fis = new java.io.FileInputStream(file);
                    net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(fis);
                    fis.close();

                    if (tag != null && tag.contains("aiagent") && tag.getBoolean("aiagent")) {
                        if (file.delete()) {
                            LOGGER.info("[AI] 已清理残留的 FakePlayer 存档: {}", file.getName());
                        }
                    }
                } catch (Exception ignored) {
                    // 读取失败说明不是我们的文件，跳过
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AI] 清理残留存档时出错: {}", e.getMessage());
        }
    }
}

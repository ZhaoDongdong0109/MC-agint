package com.aiagent;

import com.aiagent.ai.AIPlayerManager;
import com.aiagent.command.AICommand;
import com.aiagent.config.AIConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
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
}

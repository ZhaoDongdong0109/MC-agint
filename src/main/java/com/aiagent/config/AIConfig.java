package com.aiagent.config;

import com.aiagent.AIAgentMod;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * AI Agent 配置
 *
 * 使用 NeoForge Config 系统，配置保存在 config/aiagent-common.toml
 */
public class AIConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<String> API_URL =
            BUILDER.comment("AI API URL (OpenAI-compatible endpoint)")
                    .define("apiUrl", "https://token-plan-cn.xiaomimimo.com/v1");

    private static final ModConfigSpec.ConfigValue<String> API_KEY =
            BUILDER.comment("AI API Key")
                    .define("apiKey", "");

    private static final ModConfigSpec.ConfigValue<String> MODEL =
            BUILDER.comment("AI Model name")
                    .define("model", "MiMo-V2-Omni");

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static void init() {
        AIAgentMod.LOGGER.info("[AI Config] 配置已加载");
    }

    // ==================== Getters ====================

    public static String getApiUrl() {
        return API_URL.get();
    }

    public static String getApiKey() {
        return API_KEY.get();
    }

    public static String getModel() {
        return MODEL.get();
    }

    // ==================== Setters (运行时修改) ====================

    public static void setApiUrl(String url) {
        API_URL.set(url);
    }

    public static void setApiKey(String key) {
        API_KEY.set(key);
    }

    public static void setModel(String model) {
        MODEL.set(model);
    }
}

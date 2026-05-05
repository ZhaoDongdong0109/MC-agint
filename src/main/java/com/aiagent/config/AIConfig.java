package com.aiagent.config;

import com.aiagent.AIAgentMod;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * AI Agent 配置
 *
 * 使用 Forge Config 系统，配置保存在 config/aiagent-common.toml
 */
public class AIConfig {

    // Forge Config
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.ConfigValue<String> API_URL =
            BUILDER.comment("AI API URL (OpenAI-compatible endpoint)")
                    .define("apiUrl", "https://token-plan-cn.xiaomimimo.com/v1/chat/completions");

    private static final ForgeConfigSpec.ConfigValue<String> API_KEY =
            BUILDER.comment("AI API Key")
                    .define("apiKey", "");

    private static final ForgeConfigSpec.ConfigValue<String> MODEL =
            BUILDER.comment("AI Model name")
                    .define("model", "xiaomi-token-plan-cn/mimo-v2-omni");

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    /**
     * 初始化 - 在 mod 构造函数中调用
     */
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

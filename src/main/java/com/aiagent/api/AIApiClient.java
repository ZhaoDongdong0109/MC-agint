package com.aiagent.api;

import com.aiagent.AIAgentMod;
import com.aiagent.config.AIConfig;
import com.google.gson.*;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

/**
 * AI API 客户端 - 健壮版
 *
 * 改进：
 * - 支持 system message（身份和性格分离，LLM 理解更快）
 * - 聊天模式 max_tokens 降低到 150（强制简短回复）
 * - 自主思考模式 max_tokens 保持 800（需要完整 JSON）
 * - 自动重试 + 超时处理 + 解析容错
 */
public class AIApiClient {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_RETRIES = 2;
    private static final int TIMEOUT_MS = 20000;

    /**
     * 异步调用 — 自主思考模式（需要完整 JSON，max_tokens=800）
     */
    public CompletableFuture<String> chatAsync(String prompt) {
        return chatAsync(prompt, null, 800);
    }

    /**
     * 异步调用 — 聊天模式（system message + 强制简短，max_tokens=150）
     */
    public CompletableFuture<String> chatAsync(String prompt, String systemMessage) {
        return chatAsync(prompt, systemMessage, 150);
    }

    /**
     * 异步调用 — 通用版
     */
    public CompletableFuture<String> chatAsync(String prompt, String systemMessage, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> chatWithRetry(prompt, systemMessage, maxTokens, 0));
    }

    /**
     * 同步调用（在异步线程中使用）
     */
    public String chat(String prompt) {
        return chatWithRetry(prompt, null, 800, 0);
    }

    private String chatWithRetry(String prompt, String systemMessage, int maxTokens, int attempt) {
        String apiUrl = AIConfig.getApiUrl();
        String apiKey = AIConfig.getApiKey();
        String model = AIConfig.getModel();

        AIAgentMod.LOGGER.info("[API] 调用: url={}, model={}, keyLen={}, maxTokens={}",
                apiUrl, model, apiKey != null ? apiKey.length() : 0, maxTokens);

        if (apiKey == null || apiKey.isEmpty()) {
            AIAgentMod.LOGGER.warn("[API] API Key 未设置!");
            return errorResponse("未配置 API Key，使用 /ai config key <key> 设置");
        }

        try {
            String fullUrl = apiUrl;
            if (!fullUrl.endsWith("/chat/completions")) {
                fullUrl = fullUrl.replaceAll("/+$", "") + "/chat/completions";
            }

            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            // 构建请求（带 system message）
            JsonObject body = buildRequestBody(prompt, model, systemMessage, maxTokens);
            String json = GSON.toJson(body);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            AIAgentMod.LOGGER.info("[API] 响应码: {}", code);

            if (code == 429) {
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(2000 * (attempt + 1));
                    return chatWithRetry(prompt, systemMessage, maxTokens, attempt + 1);
                }
                return errorResponse("API 限流，请稍后再试");
            }

            if (code != 200) {
                String errorBody = readStream(conn.getErrorStream());
                AIAgentMod.LOGGER.warn("API error {}: {}", code, errorBody);

                if (attempt < MAX_RETRIES && code >= 500) {
                    Thread.sleep(1000 * (attempt + 1));
                    return chatWithRetry(prompt, systemMessage, maxTokens, attempt + 1);
                }
                return errorResponse("API 返回错误 " + code);
            }

            String responseBody = readStream(conn.getInputStream());
            return parseResponse(responseBody);

        } catch (java.net.SocketTimeoutException e) {
            AIAgentMod.LOGGER.warn("[API] 超时 (attempt {}/{}): {}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
            if (attempt < MAX_RETRIES) {
                return chatWithRetry(prompt, systemMessage, maxTokens, attempt + 1);
            }
            return errorResponse("API 超时，请检查网络或 API 地址");
        } catch (Exception e) {
            AIAgentMod.LOGGER.error("[API] 调用失败 (attempt {}/{}): {}", attempt + 1, MAX_RETRIES + 1, e.getMessage(), e);
            if (attempt < MAX_RETRIES) {
                return chatWithRetry(prompt, systemMessage, maxTokens, attempt + 1);
            }
            return errorResponse("API 调用失败: " + e.getMessage());
        }
    }

    /**
     * 构建请求体 — 支持 system message
     */
    private JsonObject buildRequestBody(String prompt, String model, String systemMessage, int maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.7);  // 略降，提高一致性
        body.addProperty("max_tokens", maxTokens);

        JsonArray messages = new JsonArray();

        // System message — 身份、性格、说话风格放这里
        if (systemMessage != null && !systemMessage.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemMessage);
            messages.add(sysMsg);
        }

        // User message — 当前状态和对话内容
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);

        body.add("messages", messages);
        return body;
    }

    /**
     * 解析响应 - 容错处理
     */
    private String parseResponse(String responseBody) {
        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();

            if (response.has("choices")) {
                JsonArray choices = response.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        String content = choice.getAsJsonObject("message").get("content").getAsString();
                        return cleanResponse(content);
                    }
                }
            }

            if (response.has("content")) {
                JsonArray content = response.getAsJsonArray("content");
                if (content.size() > 0) {
                    return cleanResponse(content.get(0).getAsJsonObject().get("text").getAsString());
                }
            }

            return errorResponse("无法解析 API 响应格式");

        } catch (Exception e) {
            if (responseBody != null && !responseBody.trim().isEmpty()) {
                return cleanResponse(responseBody.trim());
            }
            return errorResponse("响应解析失败");
        }
    }

    private String cleanResponse(String raw) {
        if (raw == null) return "{}";

        String cleaned = raw.trim();

        // 去掉 ```json ... ``` 包裹
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastBacktick = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastBacktick).trim();
            }
        }

        // 去掉开头的 { 和结尾的 } 以外的内容
        int jsonStart = cleaned.indexOf('{');
        int jsonEnd = cleaned.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned = cleaned.substring(jsonStart, jsonEnd + 1);
        }

        return cleaned;
    }

    private String errorResponse(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("reply", "[系统] " + message);
        obj.addProperty("action", "none");
        obj.addProperty("thought", message);
        return GSON.toJson(obj);
    }

    private String readStream(java.io.InputStream stream) {
        if (stream == null) return "";
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())) {
            return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
    }
}

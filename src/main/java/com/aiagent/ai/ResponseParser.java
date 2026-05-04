package com.aiagent.ai;

import com.google.gson.*;

/**
 * LLM 响应解析器 - 容错版
 *
 * LLM 返回的 JSON 经常有各种问题：
 * - 多了 markdown 包裹
 * - 字段缺失
 * - 字段名不对
 * - 有注释
 * - 有尾逗号
 * - 不是 JSON 是纯文本
 *
 * 这个解析器尽可能从任何格式中提取有用信息
 */
public class ResponseParser {

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * 解析 LLM 响应
     */
    public static ParsedResponse parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return ParsedResponse.empty();
        }

        String cleaned = clean(raw);

        // 尝试 JSON 解析
        try {
            JsonObject json = JsonParser.parseString(cleaned).getAsJsonObject();
            return parseJson(json);
        } catch (Exception e) {
            // 不是 JSON，当纯文本处理
            return parseAsText(raw);
        }
    }

    /**
     * 从 JSON 解析
     */
    private static ParsedResponse parseJson(JsonObject json) {
        String reply = getStringField(json, "reply", "message", "response", "text", "content");
        String action = getStringField(json, "action", "command", "do");
        String target = getStringField(json, "action_target", "target", "params", "args");
        String thought = getStringField(json, "thought", "thinking", "reason", "idea");

        // 如果 action 是 "none" 或空，当作纯聊天
        if (action == null || action.equalsIgnoreCase("none") || action.isEmpty()) {
            action = null;
        }

        return new ParsedResponse(reply, action, target, thought);
    }

    /**
     * 纯文本当作聊天回复
     */
    private static ParsedResponse parseAsText(String text) {
        // 清理一下，去掉可能的 JSON 标记
        String cleaned = text.replaceAll("[{}\"']", "").trim();
        if (cleaned.isEmpty()) {
            return ParsedResponse.empty();
        }
        return new ParsedResponse(cleaned, null, null, null);
    }

    /**
     * 从多个可能的字段名中获取值
     */
    private static String getStringField(JsonObject json, String... fieldNames) {
        for (String name : fieldNames) {
            if (json.has(name)) {
                JsonElement elem = json.get(name);
                if (elem.isJsonPrimitive()) {
                    String val = elem.getAsString();
                    if (!val.isEmpty() && !val.equalsIgnoreCase("null")) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 清理响应文本
     */
    private static String clean(String raw) {
        String s = raw.trim();

        // 去掉 ```json ... ```
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastTick = s.lastIndexOf("```");
            if (firstNl > 0 && lastTick > firstNl) {
                s = s.substring(firstNl + 1, lastTick).trim();
            }
        }

        // 提取 JSON 部分
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }

        // 去掉注释
        s = s.replaceAll("//.*$", "").trim();

        return s;
    }

    // ==================== 结果类 ====================

    public record ParsedResponse(
        String reply,       // AI 说的话
        String action,      // 要做的动作
        String actionTarget,// 动作目标
        String thought      // 内心想法
    ) {
        public static ParsedResponse empty() {
            return new ParsedResponse("", null, null, null);
        }

        public boolean hasReply() {
            return reply != null && !reply.isEmpty();
        }

        public boolean hasAction() {
            return action != null && !action.isEmpty();
        }

        public boolean isChatOnly() {
            return hasReply() && !hasAction();
        }
    }
}

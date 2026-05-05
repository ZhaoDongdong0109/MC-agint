package com.aiagent.command;

import com.aiagent.ai.AIPlayerManager;
import com.aiagent.brain.GameKnowledge;
import com.aiagent.config.AIConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /ai 命令 - 简化版，主要用于管理
 *
 * 核心交互通过游戏内聊天完成，不需要命令。
 * AI 会监听所有聊天消息，被提到名字就会回应。
 *
 * 命令:
 *   /ai spawn <name>   - 召唤 AI 玩家
 *   /ai remove <name>  - 移除 AI 玩家
 *   /ai list           - 查看所有 AI
 *   /ai config key <k> - 设置 API Key
 */
public class AICommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai")
            .then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))
                )
            )
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name")))
                )
            )
            .then(Commands.literal("list")
                .executes(ctx -> list(ctx))
            )
            .then(Commands.literal("stop")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> stop(ctx, StringArgumentType.getString(ctx, "name")))
                )
            )
            .then(Commands.literal("ask")
                .then(Commands.argument("question", StringArgumentType.greedyString())
                    .executes(ctx -> ask(ctx, StringArgumentType.getString(ctx, "question")))
                )
            )
            .then(Commands.literal("config")
                .then(Commands.literal("key")
                    .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> setKey(ctx, StringArgumentType.getString(ctx, "key")))
                    )
                )
                .then(Commands.literal("url")
                    .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> setUrl(ctx, StringArgumentType.getString(ctx, "url")))
                    )
                )
                .then(Commands.literal("model")
                    .then(Commands.argument("model", StringArgumentType.greedyString())
                        .executes(ctx -> setModel(ctx, StringArgumentType.getString(ctx, "model")))
                    )
                )
            )
        );
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("只能由玩家执行"));
            return 0;
        }

        AIPlayerManager.getInstance().spawn(
                ctx.getSource().getServer(),
                player.serverLevel(),
                name,
                player
        );

        ctx.getSource().sendSuccess(() ->
                Component.literal("§a[AI] §f已召唤: §e" + name + " §7(在聊天框里 @" + name + " 跟它说话)"),
                true
        );

        // 检查 API 配置
        String apiKey = AIConfig.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("§c[AI] §f警告: 未设置 API Key！请使用 §e/ai config key <你的key> §f设置"),
                    false
            );
        } else {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("§7[AI] API: §f" + AIConfig.getModel() + " §7@ §f" + AIConfig.getApiUrl()),
                    false
            );
        }

        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String name) {
        boolean ok = AIPlayerManager.getInstance().remove(name);
        if (ok) {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("§a[AI] §f已移除: §e" + name), true);
        } else {
            ctx.getSource().sendFailure(
                    Component.literal("§c[AI] §f没找到 §e" + name));
        }
        return ok ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        String list = AIPlayerManager.getInstance().listAll();
        ctx.getSource().sendSuccess(() ->
                Component.literal("§e[AI 玩家列表]\n§f" + list), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, String name) {
        boolean ok = AIPlayerManager.getInstance().stopGoal(name);
        if (ok) {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("§a[AI] §e" + name + " §f已停止当前任务，开始自由活动"), true);
        } else {
            ctx.getSource().sendFailure(
                    Component.literal("§c[AI] §f没找到 §e" + name));
        }
        return ok ? 1 : 0;
    }

    private static int ask(CommandContext<CommandSourceStack> ctx, String question) {
        // 直接查游戏数据
        com.aiagent.brain.GameKnowledge knowledge = new com.aiagent.brain.GameKnowledge();
        knowledge.setServer(ctx.getSource().getServer());
        String answer = knowledge.query(question);
        ctx.getSource().sendSuccess(() ->
                Component.literal("§e[游戏知识] §f" + answer), false);
        return 1;
    }

    private static int setKey(CommandContext<CommandSourceStack> ctx, String key) {
        AIConfig.setApiKey(key);
        ctx.getSource().sendSuccess(() ->
                Component.literal("§a[AI] §fAPI Key 已设置"), false);
        return 1;
    }

    private static int setUrl(CommandContext<CommandSourceStack> ctx, String url) {
        AIConfig.setApiUrl(url);
        ctx.getSource().sendSuccess(() ->
                Component.literal("§a[AI] §fAPI URL: §e" + url), false);
        return 1;
    }

    private static int setModel(CommandContext<CommandSourceStack> ctx, String model) {
        AIConfig.setModel(model);
        ctx.getSource().sendSuccess(() ->
                Component.literal("§a[AI] §f模型: §e" + model), false);
        return 1;
    }
}

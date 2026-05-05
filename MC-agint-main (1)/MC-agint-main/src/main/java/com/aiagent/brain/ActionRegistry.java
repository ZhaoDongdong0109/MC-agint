package com.aiagent.brain;

import java.util.*;

/**
 * 动作注册表 - 定义 AI 能做的所有动作
 *
 * 给 LLM 看的是人类可读的描述
 * 代码里执行的是具体的游戏操作
 *
 * 这样 LLM 就知道"我能做什么"而不是瞎猜
 */
public class ActionRegistry {

    public static final List<ActionDef> ACTIONS = List.of(

        // ===== 移动 =====
        new ActionDef("move", "向指定方向移动",
            Map.of("direction", "north|south|east|west|up|down|forward"),
            "移动几步，探索周围环境"),

        new ActionDef("goto", "走向指定坐标",
            Map.of("x", "目标X坐标", "z", "目标Z坐标"),
            "走到目标位置，自动寻路"),

        new ActionDef("follow", "跟随一个玩家",
            Map.of("player", "玩家名字"),
            "跟着指定玩家走，保持几格距离"),

        // ===== 采集 =====
        new ActionDef("mine", "挖掘附近的方块",
            Map.of("block", "方块名，如 coal_ore, stone, dirt"),
            "找到最近的该方块并挖掘"),

        new ActionDef("chop", "砍树",
            Map.of("type", "树的类型，如 oak, birch（可选）"),
            "砍附近的树，收集原木"),

        new ActionDef("dig", "向下挖",
            Map.of("depth", "挖几层（默认3）"),
            "在当前位置往下挖"),

        // ===== 放置 =====
        new ActionDef("place", "放置方块",
            Map.of("block", "方块名", "direction", "放在哪（north/south/east/west/above/below）"),
            "在指定方向放置方块"),

        // ===== 战斗 =====
        new ActionDef("attack", "攻击实体",
            Map.of("target", "目标名，如 zombie, skeleton, cow"),
            "攻击最近的该实体"),

        new ActionDef("flee", "逃跑",
            Map.of("from", "逃跑方向或威胁来源"),
            "远离危险"),

        // ===== 合成 =====
        new ActionDef("craft", "合成物品",
            Map.of("item", "要合成的物品", "count", "数量（可选）"),
            "使用背包内的材料合成物品（不需要工作台的配方）"),

        new ActionDef("smelt", "熔炼",
            Map.of("item", "要熔炼的矿石", "fuel", "燃料（如 coal）"),
            "在熔炉中熔炼矿石"),

        // ===== 装备 =====
        new ActionDef("equip", "装备物品",
            Map.of("item", "要装备的物品", "slot", "mainhand|offhand|head|chest|legs|feet"),
            "把物品装备到指定位置"),

        // ===== 使用 =====
        new ActionDef("eat", "吃东西",
            Map.of("item", "食物名"),
            "吃食物恢复饥饿值"),

        new ActionDef("use", "使用手中物品",
            Map.of("target", "使用目标（可选）"),
            "使用主手物品，如右键"),

        // ===== 社交 =====
        new ActionDef("say", "说话",
            Map.of("message", "要说的话"),
            "在聊天框说话，附近玩家都能看到"),

        new ActionDef("whisper", "私聊",
            Map.of("player", "玩家名", "message", "要说的话"),
            "对特定玩家说话"),

        // ===== 信息 =====
        new ActionDef("look", "查看周围",
            Map.of("range", "扫描范围（默认10）"),
            "仔细观察周围环境，返回详细信息"),

        new ActionDef("inventory", "查看背包",
            Map.of(),
            "查看当前背包里有什么"),

        new ActionDef("health", "查看状态",
            Map.of(),
            "查看生命值、饥饿值、装备"),

        // ===== 特殊 =====
        new ActionDef("wait", "等待",
            Map.of("ticks", "等待的tick数"),
            "原地等待指定时间"),

        new ActionDef("sleep", "睡觉",
            Map.of(),
            "如果附近有床，跳过夜晚"),

        new ActionDef("build_shelter", "建简单庇护所",
            Map.of(),
            "用现有材料建一个简单的避难小屋"),

        new ActionDef("query", "查询游戏知识",
            Map.of("question", "你想知道的问题，如'铁剑的配方''钻石有什么用'"),
            "向游戏引擎查询配方、物品信息、方块信息等")
    );

    /**
     * 获取给 LLM 的动作描述文本
     */
    public static String getActionsForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("【你可以做的动作】\n\n");

        for (ActionDef action : ACTIONS) {
            sb.append(action.name).append(": ").append(action.description).append("\n");
            if (!action.params.isEmpty()) {
                sb.append("  参数: ");
                action.params.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
                sb.append("\n");
            }
            sb.append("  说明: ").append(action.hint).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 检查动作是否合法
     */
    public static boolean isValidAction(String actionName) {
        return ACTIONS.stream().anyMatch(a -> a.name.equals(actionName));
    }

    public record ActionDef(
        String name,
        String description,
        Map<String, String> params,
        String hint
    ) {}
}

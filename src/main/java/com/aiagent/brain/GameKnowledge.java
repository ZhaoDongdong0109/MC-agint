package com.aiagent.brain;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 游戏知识库 - 直接从 MC 引擎读取数据
 *
 * 不硬编码，直接查游戏：
 * - 配方 → RecipeManager
 * - 方块 → Block Registry
 * - 物品 → Item Registry
 * - 生物 → Entity Registry
 */
public class GameKnowledge {

    private MinecraftServer server;

    public GameKnowledge() {}

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // ==================== 配方查询 ====================

    /**
     * 查找某个物品的合成配方
     */
    public String getRecipe(String itemName) {
        if (server == null) return "服务器未就绪";

        RecipeManager recipeManager = server.getRecipeManager();
        RegistryAccess registryAccess = server.registryAccess();

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(itemName).append(" 的配方】\n");

        List<Recipe<?>> found = new ArrayList<>();

        // 遍历所有合成配方
        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = recipe.getResultItem(registryAccess);
            if (result.getHoverName().getString().toLowerCase().contains(itemName.toLowerCase())) {
                found.add(recipe);
            }
        }

        // 也查熔炼配方
        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(RecipeType.SMELTING)) {
            ItemStack result = recipe.getResultItem(registryAccess);
            if (result.getHoverName().getString().toLowerCase().contains(itemName.toLowerCase())) {
                found.add(recipe);
            }
        }

        if (found.isEmpty()) {
            return "找不到 " + itemName + " 的配方";
        }

        for (Recipe<?> recipe : found) {
            ItemStack result = recipe.getResultItem(registryAccess);
            sb.append(String.format("  %s x%d\n", result.getHoverName().getString(), result.getCount()));

            if (recipe instanceof CraftingRecipe crafting) {
                sb.append("  材料: ");
                for (var ingredient : crafting.getIngredients()) {
                    if (!ingredient.isEmpty()) {
                        ItemStack[] items = ingredient.getItems();
                        if (items.length > 0) {
                            sb.append(items[0].getHoverName().getString()).append(" ");
                        }
                    }
                }
                sb.append("\n");
            } else if (recipe instanceof SmeltingRecipe smelting) {
                sb.append("  熔炼: ").append(smelting.getIngredients().get(0).getItems()[0].getHoverName().getString())
                  .append(" → ").append(result.getHoverName().getString()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 查找物品能用来合成什么
     */
    public String getUsedIn(String itemName) {
        if (server == null) return "服务器未就绪";

        RecipeManager recipeManager = server.getRecipeManager();
        RegistryAccess registryAccess = server.registryAccess();

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(itemName).append(" 可以合成】\n");

        int count = 0;
        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            for (var ingredient : recipe.getIngredients()) {
                if (!ingredient.isEmpty()) {
                    for (ItemStack stack : ingredient.getItems()) {
                        if (stack.getHoverName().getString().toLowerCase().contains(itemName.toLowerCase())) {
                            ItemStack result = recipe.getResultItem(registryAccess);
                            sb.append("  - ").append(result.getHoverName().getString())
                              .append(" x").append(result.getCount()).append("\n");
                            count++;
                            if (count >= 10) {
                                sb.append("  ... 还有更多");
                                return sb.toString();
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (count == 0) {
            return itemName + " 不能用来合成任何东西";
        }

        return sb.toString();
    }

    /**
     * 列出玩家背包里能合成的东西
     */
    public String getCraftableFromInventory(ServerPlayer player) {
        if (server == null) return "服务器未就绪";

        RecipeManager recipeManager = server.getRecipeManager();
        RegistryAccess registryAccess = server.registryAccess();

        // 统计背包物品
        Map<Item, Integer> inventory = new HashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                inventory.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【当前可以合成】\n");

        int count = 0;
        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (recipe instanceof CraftingRecipe crafting) {
                if (canCraft(crafting, inventory)) {
                    ItemStack result = crafting.getResultItem(registryAccess);
                    sb.append("  ").append(result.getHoverName().getString())
                      .append(" x").append(result.getCount()).append("\n");
                    count++;
                    if (count >= 15) {
                        sb.append("  ...");
                        break;
                    }
                }
            }
        }

        if (count == 0) {
            return "当前背包材料什么都合不成";
        }

        return sb.toString();
    }

    private boolean canCraft(CraftingRecipe recipe, Map<Item, Integer> inventory) {
        // 简化检查：看配方需要的材料背包里有没有
        Map<Item, Integer> needed = new HashMap<>();
        for (var ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ItemStack[] items = ingredient.getItems();
                if (items.length > 0) {
                    needed.merge(items[0].getItem(), 1, Integer::sum);
                }
            }
        }

        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            Integer have = inventory.get(entry.getKey());
            if (have == null || have < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ==================== 方块查询 ====================

    /**
     * 查方块信息
     */
    public String getBlockInfo(String blockName) {
        if (server == null) return "";

        StringBuilder sb = new StringBuilder();

        for (Block block : BuiltInRegistries.BLOCK) {
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (name.contains(blockName.toLowerCase()) ||
                block.getName().getString().toLowerCase().contains(blockName.toLowerCase())) {

                BlockState defaultState = block.defaultBlockState();
                sb.append("方块: ").append(block.getName().getString()).append("\n");
                sb.append("  ID: ").append(name).append("\n");
                sb.append("  硬度: ").append(defaultState.getDestroySpeed(null, null)).append("\n");
                sb.append("  是否可被工具挖掘: ").append(!defaultState.requiresCorrectToolForDrops()).append("\n");

                // 推荐工具
                String tool = guessBestTool(block);
                if (tool != null) {
                    sb.append("  推荐工具: ").append(tool).append("\n");
                }

                break;
            }
        }

        return sb.toString();
    }

    private String guessBestTool(Block block) {
        // 根据方块类型猜测最佳工具
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (name.contains("ore") || name.contains("stone") || name.contains("cobble")) return "镐(pickaxe)";
        if (name.contains("log") || name.contains("wood") || name.contains("plank")) return "斧(axe)";
        if (name.contains("dirt") || name.contains("grass") || name.contains("sand") || name.contains("gravel")) return "铲(shovel)";
        if (name.contains("leaves")) return "剪刀(shears)";
        return null;
    }

    // ==================== 物品查询 ====================

    /**
     * 查物品信息
     */
    public String getItemInfo(String itemName) {
        if (server == null) return "";

        StringBuilder sb = new StringBuilder();

        for (Item item : BuiltInRegistries.ITEM) {
            String regName = BuiltInRegistries.ITEM.getKey(item).toString();
            ItemStack stack = new ItemStack(item);

            if (regName.contains(itemName.toLowerCase()) ||
                stack.getHoverName().getString().toLowerCase().contains(itemName.toLowerCase())) {

                sb.append("物品: ").append(stack.getHoverName().getString()).append("\n");
                sb.append("  ID: ").append(regName).append("\n");
                sb.append("  最大堆叠: ").append(item.getMaxStackSize()).append("\n");

                if (stack.isEdible()) {
                    sb.append("  可食用: 是\n");
                    var foodProps = stack.getFoodProperties(null);
                    if (foodProps != null) {
                        sb.append("  营养值: ").append(foodProps.getNutrition()).append("\n");
                        sb.append("  饱和度: ").append(foodProps.getSaturationModifier()).append("\n");
                    }
                }

                if (item.getMaxDamage() > 0) {
                    sb.append("  耐久: ").append(item.getMaxDamage()).append("\n");
                }

                // 查配方
                sb.append(getRecipe(stack.getHoverName().getString()));

                break;
            }
        }

        return sb.toString();
    }

    // ==================== 综合状态描述 ====================

    /**
     * 给 LLM 用的完整游戏知识文本
     */
    public String getKnowledgeForPrompt(ServerPlayer player) {
        StringBuilder sb = new StringBuilder();

        sb.append("【游戏知识 - 从游戏引擎实时读取】\n\n");

        // 基础规则（这些是固定规则，不需要每次查）
        sb.append("基础规则:\n");
        sb.append("  工具等级: 木 < 石 < 铁 < 钻石 < 下界合金\n");
        sb.append("  挖矿要求: 煤矿→任意镐, 铁矿→石镐, 金矿→铁镐, 钻石→铁镐\n");
        sb.append("  时间: 白天安全，夜晚怪物生成\n");
        sb.append("  苦力怕靠近会爆炸，保持距离!\n\n");

        // 当前可合成
        if (player != null) {
            sb.append(getCraftableFromInventory(player)).append("\n");
        }

        return sb.toString();
    }

    /**
     * 动态查询：AI 想知道某个东西的配方时调用
     */
    public String query(String question) {
        if (server == null) return "服务器未就绪";

        String lower = question.toLowerCase();

        // "XX的配方" / "怎么合成XX"
        if (lower.contains("配方") || lower.contains("合成") || lower.contains("怎么造")) {
            String item = extractItemName(lower, "配方", "合成", "怎么造", "怎么做");
            if (item != null) return getRecipe(item);
        }

        // "XX能做什么" / "XX有什么用"
        if (lower.contains("能做") || lower.contains("有什么用") || lower.contains("用途")) {
            String item = extractItemName(lower, "能做", "有什么用", "用途");
            if (item != null) return getUsedIn(item);
        }

        // "XX是什么" / "XX的信息"
        if (lower.contains("是什么") || lower.contains("信息")) {
            String thing = extractItemName(lower, "是什么", "信息");
            if (thing != null) {
                String blockInfo = getBlockInfo(thing);
                if (!blockInfo.isEmpty()) return blockInfo;
                return getItemInfo(thing);
            }
        }

        return "我不太确定你想问什么";
    }

    private String extractItemName(String text, String... keywords) {
        for (String keyword : keywords) {
            int idx = text.indexOf(keyword);
            if (idx > 0) {
                return text.substring(0, idx).trim();
            }
            if (idx + keyword.length() < text.length()) {
                return text.substring(idx + keyword.length()).trim();
            }
        }
        return null;
    }
}

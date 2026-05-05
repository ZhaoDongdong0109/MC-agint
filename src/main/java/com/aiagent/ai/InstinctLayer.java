package com.aiagent.ai;

import com.aiagent.AIAgentMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 本能层 — 小脑级行为，行为树驱动，不需要 LLM
 *
 * 处理所有"不需要思考"的日常行为：
 * - 走路/寻路
 * - 挖矿（已知目标）
 * - 砍树（已知目标）
 * - 合成（已知配方）
 * - 捡东西
 * - 吃饭
 * - 闲逛探索
 *
 * 设计思路：
 * 人类玩家 90% 的时间在做"不用想"的事。
 * 只有遇到新情况、需要决策时，才调 LLM（思考层）。
 */
public class InstinctLayer {

    private final ServerPlayer player;
    private final String name;

    // 当前本能行为
    private InstinctState state = InstinctState.IDLE;
    private int stateTicks = 0;

    // 移动目标
    private BlockPos moveTarget = null;
    private int moveFailCount = 0;

    // 挖掘目标
    private BlockPos mineTarget = null;
    private int mineTicks = 0;
    private int mineTotalTicks = 0;

    // 合成目标
    private String craftItem = null;

    // 探索
    private BlockPos exploreTarget = null;
    private int exploreSteps = 0;

    // 随机
    private final Random random = new Random();

    public enum InstinctState {
        IDLE,           // 闲着
        WALKING,        // 走路中
        MINING,         // 挖掘中
        CHOPPING,       // 砍树中
        CRAFTING,       // 合成中
        EATING,         // 吃东西中
        PICKING_UP,     // 捡东西中
        EXPLORING,      // 闲逛中
        SLEEPING,       // 睡觉中
        SEEKING_FOOD,   // 找食物中
        SEEKING_SHELTER // 找庇护所中
    }

    public InstinctLayer(ServerPlayer player, String name) {
        this.player = player;
        this.name = name;
    }

    /**
     * 每 tick 调用
     * @return true 表示本能层在处理事情，不需要调 LLM
     */
    public boolean tick() {
        stateTicks++;

        switch (state) {
            case IDLE:
                return false; // 空闲，交给上层决定

            case WALKING:
                return tickWalking();

            case MINING:
                return tickMining();

            case CHOPPING:
                return tickChopping();

            case CRAFTING:
                return tickCrafting();

            case EATING:
                return tickEating();

            case PICKING_UP:
                return tickPickingUp();

            case EXPLORING:
                return tickExploring();

            case SEEKING_FOOD:
                return tickSeekingFood();

            case SEEKING_SHELTER:
                return tickSeekingShelter();

            default:
                return false;
        }
    }

    // ==================== 行为启动 ====================

    /**
     * 开始走路 — 立即执行，不需要 LLM
     */
    public void startWalking(BlockPos target) {
        this.moveTarget = target;
        this.state = InstinctState.WALKING;
        this.stateTicks = 0;
        this.moveFailCount = 0;
        AIAgentMod.LOGGER.debug("[{}] 本能：走向 {}", name, target);
    }

    /**
     * 开始挖掘 — 立即执行
     */
    public void startMining(BlockPos target) {
        this.mineTarget = target;
        this.state = InstinctState.MINING;
        this.stateTicks = 0;
        this.mineTicks = 0;

        // 计算挖掘时间（简化版）
        BlockState blockState = player.level().getBlockState(target);
        this.mineTotalTicks = (int) (blockState.getDestroySpeed(null, target) * 20);
        if (mineTotalTicks < 1) mineTotalTicks = 1;
        if (mineTotalTicks > 200) mineTotalTicks = 200;

        AIAgentMod.LOGGER.debug("[{}] 本能：挖掘 {}（{} ticks）", name, target, mineTotalTicks);
    }

    /**
     * 开始砍树
     */
    public void startChopping(BlockPos treeBase) {
        // 找到树的最底部原木
        BlockPos current = treeBase;
        while (player.level().getBlockState(current.below()).is(Blocks.OAK_LOG)
                || player.level().getBlockState(current.below()).is(Blocks.BIRCH_LOG)
                || player.level().getBlockState(current.below()).is(Blocks.SPRUCE_LOG)) {
            current = current.below();
        }
        startMining(current); // 复用挖掘逻辑
        this.state = InstinctState.CHOPPING;
    }

    /**
     * 开始合成
     */
    public void startCrafting(String itemName) {
        this.craftItem = itemName;
        this.state = InstinctState.CRAFTING;
        this.stateTicks = 0;
        AIAgentMod.LOGGER.debug("[{}] 本能：合成 {}", name, itemName);
    }

    /**
     * 开始吃东西
     */
    public void startEating() {
        this.state = InstinctState.EATING;
        this.stateTicks = 0;
    }

    /**
     * 开始闲逛探索
     */
    public void startExploring() {
        this.exploreSteps = 0;
        this.state = InstinctState.EXPLORING;
        this.stateTicks = 0;
        pickNewExploreTarget();
    }

    /**
     * 开始找食物
     */
    public void startSeekingFood() {
        this.state = InstinctState.SEEKING_FOOD;
        this.stateTicks = 0;
    }

    /**
     * 开始找庇护所（天黑了）
     */
    public void startSeekingShelter() {
        this.state = InstinctState.SEEKING_SHELTER;
        this.stateTicks = 0;
    }

    // ==================== 行为执行 ====================

    private boolean tickWalking() {
        if (moveTarget == null) {
            state = InstinctState.IDLE;
            return false;
        }

        // 到达目标
        if (player.blockPosition().distManhattan(moveTarget) <= 2) {
            state = InstinctState.IDLE;
            moveTarget = null;
            return false;
        }

        // 简单寻路：往目标方向移动
        Vec3 dir = Vec3.atCenterOf(moveTarget).subtract(player.position()).normalize();
        player.move(MoverType.SELF, new Vec3(dir.x * 0.2, 0, dir.z * 0.2));

        // 处理台阶
        BlockPos feet = player.blockPosition();
        BlockPos ahead = feet.offset((int) Math.signum(dir.x), 0, (int) Math.signum(dir.z));
        BlockState aheadBlock = player.level().getBlockState(ahead);
        BlockState aboveAhead = player.level().getBlockState(ahead.above());

        if (!aheadBlock.isAir() && aboveAhead.isAir()) {
            // 有台阶，跨上去
            player.move(MoverType.SELF, new Vec3(0, 0.6, 0));
        }

        // 超时保护
        if (stateTicks > 200) { // 10 秒
            state = InstinctState.IDLE;
            moveTarget = null;
            moveFailCount++;
        }

        return true;
    }

    private boolean tickMining() {
        if (mineTarget == null) {
            state = InstinctState.IDLE;
            return false;
        }

        // 检查方块还在不在
        BlockState blockState = player.level().getBlockState(mineTarget);
        if (blockState.isAir()) {
            // 挖完了
            state = InstinctState.IDLE;
            mineTarget = null;
            return false;
        }

        // 太远了，走近点
        double dist = player.blockPosition().distSqr(mineTarget);
        if (dist > 6) { // 超过 ~2.5 格
            Vec3 dir = Vec3.atCenterOf(mineTarget).subtract(player.position()).normalize();
            player.move(MoverType.SELF, new Vec3(dir.x * 0.2, 0, dir.z * 0.2));
            return true;
        }

        // 模拟挖掘（简化版：直接在一定 tick 后破坏方块）
        mineTicks++;
        player.level().destroyBlock(mineTarget, true, player);

        // 简化：每 tick 尝试破坏（实际应该按硬度计算）
        if (mineTicks >= mineTotalTicks) {
            player.level().destroyBlock(mineTarget, true, player);
            state = InstinctState.IDLE;
            mineTarget = null;
        }

        return true;
    }

    /**
     * 砍树 — 和挖掘逻辑相同，但会持续砍整棵树
     */
    private boolean tickChopping() {
        if (mineTarget == null) {
            state = InstinctState.IDLE;
            return false;
        }

        // 检查当前方块还在不在
        BlockState blockState = player.level().getBlockState(mineTarget);
        if (blockState.isAir()) {
            // 砍完了这一格，检查上面还有没有原木
            BlockPos above = mineTarget.above();
            BlockState aboveState = player.level().getBlockState(above);
            String blockName = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getKey(aboveState.getBlock()).getPath();
            if (blockName.contains("log")) {
                // 继续砍上面的
                mineTarget = above;
                mineTicks = 0;
                mineTotalTicks = (int) (aboveState.getDestroySpeed(null, above) * 20);
                if (mineTotalTicks < 1) mineTotalTicks = 1;
                return true;
            } else {
                // 树砍完了
                state = InstinctState.IDLE;
                mineTarget = null;
                return false;
            }
        }

        // 太远了，走近点
        double dist = player.blockPosition().distSqr(mineTarget);
        if (dist > 6) {
            Vec3 dir = Vec3.atCenterOf(mineTarget).subtract(player.position()).normalize();
            player.move(MoverType.SELF, new Vec3(dir.x * 0.2, 0, dir.z * 0.2));
            return true;
        }

        // 挖掘
        mineTicks++;
        if (mineTicks >= mineTotalTicks) {
            player.level().destroyBlock(mineTarget, true, player);
            mineTicks = 0;
            // 不切换状态，让上面的逻辑检查是否继续砍
        }

        return true;
    }

    private boolean tickCrafting() {
        if (craftItem == null) {
            state = InstinctState.IDLE;
            return false;
        }

        // 合成逻辑交给思考层处理（需要查配方）
        // 本能层只负责标记状态
        if (stateTicks > 60) {
            state = InstinctState.IDLE;
            craftItem = null;
        }
        return true;
    }

    private boolean tickEating() {
        // MC 吃东西需要 32 tick
        if (stateTicks >= 32) {
            state = InstinctState.IDLE;
            return false;
        }
        return true;
    }

    private boolean tickPickingUp() {
        // 捡附近掉落物
        AABB box = new AABB(player.blockPosition()).inflate(3);
        List<ItemEntity> items = player.level().getEntitiesOfClass(ItemEntity.class, box);

        if (items.isEmpty()) {
            state = InstinctState.IDLE;
            return false;
        }

        // 走向最近的物品
        ItemEntity nearest = items.get(0);
        Vec3 dir = nearest.position().subtract(player.position()).normalize();
        player.move(MoverType.SELF, new Vec3(dir.x * 0.15, 0, dir.z * 0.15));

        if (stateTicks > 40) {
            state = InstinctState.IDLE;
        }
        return true;
    }

    private boolean tickExploring() {
        if (exploreTarget == null || stateTicks % 40 == 0) {
            pickNewExploreTarget();
        }

        if (exploreTarget != null) {
            Vec3 dir = Vec3.atCenterOf(exploreTarget).subtract(player.position()).normalize();
            player.move(MoverType.SELF, new Vec3(dir.x * 0.15, 0, dir.z * 0.15));
        }

        exploreSteps++;
        if (exploreSteps > 50) { // 逛够了
            state = InstinctState.IDLE;
            return false;
        }

        return true;
    }

    private boolean tickSeekingFood() {
        // 检查背包里有没有食物
        if (hasFood()) {
            startEating();
            return true;
        }

        // 找附近的动物（潜在食物来源）
        AABB box = new AABB(player.blockPosition()).inflate(20);
        var animals = player.level().getEntitiesOfClass(
                net.minecraft.world.entity.animal.Animal.class, box);

        if (!animals.isEmpty()) {
            // 走向动物
            var nearest = animals.get(0);
            Vec3 dir = nearest.position().subtract(player.position()).normalize();
            player.move(MoverType.SELF, new Vec3(dir.x * 0.2, 0, dir.z * 0.2));
        }

        if (stateTicks > 200) {
            state = InstinctState.IDLE; // 找不到算了
        }
        return true;
    }

    private boolean tickSeekingShelter() {
        // 简单策略：找一个封闭空间或者挖个洞
        // 这个比较复杂，先简单处理
        if (stateTicks > 100) {
            state = InstinctState.IDLE;
        }
        return true;
    }

    // ==================== 工具方法 ====================

    private void pickNewExploreTarget() {
        BlockPos pos = player.blockPosition();
        int dx = random.nextInt(40) - 20;
        int dz = random.nextInt(40) - 20;
        exploreTarget = pos.offset(dx, 0, dz);
    }

    private boolean hasFood() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem().getFoodProperties() != null) {
                return true;
            }
        }
        return false;
    }

    // ==================== 状态查询 ====================

    public InstinctState getState() {
        return state;
    }

    public boolean isBusy() {
        return state != InstinctState.IDLE;
    }

    public String getStatusDescription() {
        return switch (state) {
            case IDLE -> "闲着";
            case WALKING -> "走路中";
            case MINING -> "挖掘中";
            case CHOPPING -> "砍树中";
            case CRAFTING -> "合成中";
            case EATING -> "吃东西中";
            case PICKING_UP -> "捡东西中";
            case EXPLORING -> "闲逛中";
            case SEEKING_FOOD -> "找食物中";
            case SEEKING_SHELTER -> "找庇护所中";
            case SLEEPING -> "睡觉中";
        };
    }

    public void forceIdle() {
        state = InstinctState.IDLE;
        moveTarget = null;
        mineTarget = null;
        craftItem = null;
    }
}

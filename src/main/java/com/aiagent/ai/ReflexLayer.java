package com.aiagent.ai;

import com.aiagent.AIAgentMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 反射层 — 脊髓级反应，纯本地，零延迟
 *
 * 每 tick 最先执行，不经过任何思考。
 * 就像人碰到烫的东西会先缩手再想怎么回事。
 *
 * 触发条件：危险检测 → 立即执行 → 不调 LLM
 */
public class ReflexLayer {

    private final ServerPlayer player;
    private final String name;

    // 反射状态
    private boolean active = false;
    private String reflexReason = "";

    // 冷却（防止反复触发）
    private int dodgeCooldown = 0;

    public ReflexLayer(ServerPlayer player, String name) {
        this.player = player;
        this.name = name;
    }

    /**
     * 每 tick 调用 — 检测危险并立即反应
     * @return true 表示反射层接管了行为，上层不应干预
     */
    public boolean tick() {
        if (dodgeCooldown > 0) dodgeCooldown--;

        // 1. 闪避投射物（箭、火球）
        if (dodgeProjectile()) return true;

        // 2. 苦力怕靠近 → 立刻跑
        if (fleeFromCreeper()) return true;

        // 3. 掉落悬崖 → 试图抓墙
        if (preventFall()) return true;

        // 4. 着火 → 找水
        if (extinguishFire()) return true;

        // 5. 溺水 → 往上游
        if (preventDrowning()) return true;

        // 6. 生命值极低 → 逃跑 + 吃东西
        if (criticalHealth()) return true;

        active = false;
        return false;
    }

    // ==================== 具体反射 ====================

    /**
     * 闪避投射物 — 检测飞来的箭/火球，侧移闪避
     */
    private boolean dodgeProjectile() {
        if (dodgeCooldown > 0) return false;

        AABB box = new AABB(player.blockPosition()).inflate(5);
        List<Projectile> projectiles = player.level().getEntitiesOfClass(
                Projectile.class, box, p -> p.getDeltaMovement().lengthSqr() > 0.01 && p.getOwner() != player);

        if (projectiles.isEmpty()) return false;

        // 计算闪避方向（垂直于投射物飞行方向）
        Projectile nearest = projectiles.get(0);
        Vec3 projDir = nearest.getDeltaMovement().normalize();
        // 侧向闪避
        double dodgeX = -projDir.z;
        double dodgeZ = projDir.x;

        player.move(MoverType.SELF, new Vec3(dodgeX * 0.5, 0, dodgeZ * 0.5));
        dodgeCooldown = 10;

        active = true;
        reflexReason = "闪避投射物";
        AIAgentMod.LOGGER.debug("[{}] 反射：闪避投射物", name);
        return true;
    }

    /**
     * 苦力怕靠近 — 10 格内发现苦力怕立刻跑
     */
    private boolean fleeFromCreeper() {
        AABB box = new AABB(player.blockPosition()).inflate(10);
        List<net.minecraft.world.entity.monster.Creeper> creepers =
                player.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, box);

        if (creepers.isEmpty()) return false;

        // 往苦力怕反方向跑
        net.minecraft.world.entity.monster.Creeper nearest = creepers.get(0);
        Vec3 awayDir = player.position().subtract(nearest.position()).normalize();

        // 跑步速度
        player.move(MoverType.SELF, new Vec3(awayDir.x * 0.35, 0, awayDir.z * 0.35));

        active = true;
        reflexReason = "苦力怕！快跑！";
        AIAgentMod.LOGGER.debug("[{}] 反射：逃离苦力怕", name);
        return true;
    }

    /**
     * 防止摔落 — 下落超过 3 格时尝试抓墙
     */
    private boolean preventFall() {
        if (player.fallDistance < 3.0) return false;

        // 检测下方是否有方块可以落脚
        BlockPos below = player.blockPosition().below();
        BlockState state = player.level().getBlockState(below);

        if (!state.isAir()) return false; // 下面有方块，没事

        // 尝试往旁边移动
        for (var dir : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
            BlockPos side = player.blockPosition().relative(dir);
            BlockState sideState = player.level().getBlockState(side);
            if (!sideState.isAir()) {
                // 往这个方向移动
                Vec3 push = new Vec3(dir.getStepX() * 0.3, 0, dir.getStepZ() * 0.3);
                player.move(MoverType.SELF, push);
                active = true;
                reflexReason = "要摔死了！抓墙！";
                return true;
            }
        }

        return false;
    }

    /**
     * 着火 — 找附近的水
     */
    private boolean extinguishFire() {
        if (!player.isOnFire()) return false;

        // 找最近的水
        BlockPos pos = player.blockPosition();
        for (int r = 1; r <= 5; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos check = pos.offset(x, 0, z);
                    BlockState state = player.level().getBlockState(check);
                    if (state.is(Blocks.WATER)) {
                        // 往水的方向移动
                        Vec3 toWater = Vec3.atCenterOf(check).subtract(player.position()).normalize();
                        player.move(MoverType.SELF, new Vec3(toWater.x * 0.3, 0, toWater.z * 0.3));
                        active = true;
                        reflexReason = "着火了！找水！";
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 溺水 — 往上游
     */
    private boolean preventDrowning() {
        if (player.getAirSupply() > 10) return false;

        // 往上游
        player.move(MoverType.SELF, new Vec3(0, 0.3, 0));
        active = true;
        reflexReason = "要淹死了！往上游！";
        return true;
    }

    /**
     * 生命值极低 — 逃跑并尝试吃东西
     */
    private boolean criticalHealth() {
        if (player.getHealth() > 6) return false; // 3 颗心以下

        // 检查附近有没有怪
        AABB box = new AABB(player.blockPosition()).inflate(8);
        List<Monster> monsters = player.level().getEntitiesOfClass(Monster.class, box);

        if (monsters.isEmpty()) return false;

        // 往怪物反方向跑
        Monster nearest = monsters.get(0);
        Vec3 awayDir = player.position().subtract(nearest.position()).normalize();
        player.move(MoverType.SELF, new Vec3(awayDir.x * 0.35, 0, awayDir.z * 0.35));

        active = true;
        reflexReason = "血量太低！快跑！";
        return true;
    }

    // ==================== 状态查询 ====================

    public boolean isActive() {
        return active;
    }

    public String getReason() {
        return reflexReason;
    }
}

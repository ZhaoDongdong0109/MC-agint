package com.aiagent.ai;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 自定义 FakePlayer - 阻止玩家数据被保存到存档
 *
 * 核心问题：Forge 的 FakePlayerFactory 创建的 FakePlayer 会在关服时
 * 被一起保存到 playerdata/<uuid>.dat，重启时反序列化卡死。
 *
 * 解决：重写 save 相关方法，确保这个"假玩家"不会污染存档。
 */
public class AIFakePlayer extends ServerPlayer {

    public AIFakePlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile);
    }

    /**
     * 重写保存方法 - 写入标记数据
     * 即使这个玩家出现在 player list 里，存档里也不会有有效数据
     */
    @Override
    public void save(CompoundTag tag) {
        // 只写身份标记，不写位置/背包/血量
        tag.putString("id", "aiagent:" + this.getName().getString());
        tag.putBoolean("aiagent", true);  // 标记为 AI，方便识别
        // 不调用 super，避免写入完整数据
    }
}

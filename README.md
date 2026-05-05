# AI Agent Mod - Minecraft

一个让 AI 像真人玩家一样生活在你的 Minecraft 世界里的 Mod。

## 它是什么

不是命令工具，不是 NPC，是一个**独立的 AI 玩家**。

- 🧠 它有自己的生活，会自己探索、打怪、采集、建造
- 💬 你在聊天框里 @它的名字，它会像朋友一样回应你
- 🤔 它有自己的想法，可以拒绝，可以邀请你一起做事
- 📝 它有记忆，记得你们一起经历过的事
- 🌍 没人理它的时候，它过自己的日子

## 三层大脑架构

AI 的行为由三层系统驱动，就像真人玩家一样：

```
┌────────────────────────────────────────────────┐
│  🧠 思考层（大脑）                               │
│  规划、对话、复杂决策                            │
│  → 只在需要时调 LLM，每 6-12 秒一次             │
├────────────────────────────────────────────────┤
│  💪 本能层（小脑）                               │
│  走路、挖矿、砍树、捡东西、吃东西、闲逛         │
│  → 行为树驱动，不调 LLM，0 延迟                 │
├────────────────────────────────────────────────┤
│  ⚡ 反射层（脊髓）                               │
│  闪避箭矢、逃离苦力怕、防摔落、着火找水         │
│  → 纯本地，0 tick 响应                          │
└────────────────────────────────────────────────┘
        优先级：反射 > 本能 > 思考
```

**效果：** 90% 的动作本地执行，零延迟。LLM 调用减少 70-80%，省 API 费用。

## 快速开始

### 1. 下载 Mod JAR

从 [Releases](../../releases) 页面下载最新的 `ai-agent-1.0.0.jar`

### 2. 安装

1. 打开 PCL2，创建一个 **Forge 1.20.1** 实例
2. 启动一次游戏后关闭
3. 把 `ai-agent-1.0.0.jar` 放到 `.minecraft/mods/` 文件夹
4. 重启游戏

### 3. 配置 API

在游戏内输入：

```
/ai config key sk-your-api-key
/ai config model gpt-4o-mini
```

支持 OpenAI、DeepSeek、Ollama 等兼容接口。

### 4. 使用

```
/ai spawn 小赵        # 召唤 AI 玩家
@小赵 你在干嘛呢      # 在聊天框跟它说话
/ai list              # 查看所有 AI
/ai status 小赵       # 查看 AI 状态
/ai stop 小赵         # 让 AI 停止当前目标
/ai remove 小赵       # 移除 AI
```

## 从源码构建

需要 JDK 17 + Forge MDK 环境。

```bash
git clone https://github.com/ZhaoDongdong0109/MC-agint.git
cd MC-agint
./gradlew build
```

构建产物在 `build/libs/` 目录。

> **国内用户**：如果遇到证书错误，运行 `./gradlew build -Dnet.minecraftforge.gradle.check.certs=false`

## 自动构建

推送到 `main` 分支后，GitHub Actions 会自动构建并发布 JAR 到 Releases。

## 项目结构

```
src/main/java/com/aiagent/
├── AIAgentMod.java                # 入口 + 事件监听
├── ai/
│   ├── AIPlayerManager.java       # FakePlayer 管理 + 持久化
│   ├── AIAutonomousCore.java      # 🧠 思考层：LLM 决策 + 调度
│   ├── ReflexLayer.java           # ⚡ 反射层：危险检测，0 tick 响应
│   ├── InstinctLayer.java         # 💪 本能层：行为树驱动日常行为
│   ├── ConversationManager.java   # 对话 + 性格 + system message
│   └── ResponseParser.java        # 容错 JSON 解析
├── api/
│   └── AIApiClient.java           # LLM API 客户端（异步 + 重试）
├── brain/
│   ├── ActionRegistry.java        # 动作注册表
│   ├── BehaviorStateMachine.java  # 状态机
│   └── GameKnowledge.java         # 动态游戏知识（配方/方块/物品）
├── command/
│   └── AICommand.java             # /ai 管理命令
├── config/
│   └── AIConfig.java              # 配置持久化
└── memory/
    └── MemoryManager.java         # 本地记忆存储
```

## 技术特点

### 🎮 真实游戏行为
- **真实移动**：碰撞检测 + 台阶处理，不是瞬移
- **真实合成**：MC 原版配方系统，消耗材料产出成品
- **真实放置**：BlockItem.place() 触发完整放置流程
- **连续砍树**：砍完一棵原木自动检查上面继续砍

### ⚡ 性能优化
- **异步 LLM**：不阻塞服务器主线程
- **三层调度**：90% 动作本地执行，不调 API
- **快速决策**：天黑找床、饿了找吃的、捡掉落物，不经过 LLM
- **System Message**：身份和对话分离，LLM 理解更快

### 🧠 智能行为
- **随机性格**：每个 AI 首次召唤时生成独特性格
- **本地记忆**：JSON 文件持久化，记得发生过的事
- **危险反射**：苦力怕靠近 0 tick 逃跑，不等 API
- **游戏感知**：知道时间、天气、附近实体，说话带 MC 味

### 💬 自然对话
- **短句回复**：每句不超 15 字，像真人玩家说话
- **MC 黑话**：会说"撸树""挖矿""肝""草方块"
- **情绪表达**：有语气词，有性格，不是机器人

## 许可

MIT

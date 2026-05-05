# AI Agent Mod - Minecraft

一个让 AI 像真人玩家一样生活在你的 Minecraft 世界里的 Mod。

## 它是什么

不是命令工具，不是 NPC，是一个**独立的 AI 玩家**。

- 它有自己的生活，会自己探索、打怪、采集、建造
- 你在聊天框里 @它的名字，它会像朋友一样回应你
- 它有自己的想法，可以拒绝，可以邀请你一起做事
- 它有记忆，记得你们一起经历过的事
- 没人理它的时候，它过自己的日子

## 快速开始（PCL2 用户）

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

## 自动构建

推送到 `main` 分支后，GitHub Actions 会自动构建并发布 JAR 到 Releases。

## 项目结构

```
src/main/java/com/aiagent/
├── AIAgentMod.java              # 入口 + 事件监听
├── ai/
│   ├── AIPlayerManager.java     # FakePlayer 管理
│   ├── AIAutonomousCore.java    # 自主核心（感知→思考→行动）
│   ├── ConversationManager.java # 对话 + 性格系统
│   └── ResponseParser.java      # 容错 JSON 解析
├── api/
│   └── AIApiClient.java         # LLM API 客户端（同步+异步）
├── brain/
│   ├── ActionRegistry.java      # 动作注册表
│   ├── BehaviorStateMachine.java# 状态机
│   └── GameKnowledge.java       # 动态游戏知识
├── command/
│   └── AICommand.java           # /ai 管理命令
├── config/
│   └── AIConfig.java            # 配置持久化
└── memory/
    └── MemoryManager.java       # 本地记忆存储
```

## 技术特点

- **真实移动**：碰撞检测 + 台阶处理，不是瞬移
- **真实合成**：MC 原版配方系统，消耗材料产出成品
- **真实放置**：BlockItem.place() 触发完整放置流程
- **异步 LLM**：不阻塞服务器主线程
- **随机性格**：每个 AI 首次召唤时生成独特性格
- **本地记忆**：JSON 文件持久化，记得发生过的事

## 许可

MIT

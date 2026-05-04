package com.aiagent.brain;

/**
 * 状态机 - 管理 AI 的高层行为状态
 *
 * 不是每 tick 都调 LLM，太贵了。
 * 而是在状态转换时调一次 LLM 做决策，
 * 然后在当前状态下执行预定义的行为。
 *
 * 状态流转：
 * IDLE → PLANNING → EXECUTING → VERIFYING → (完成/重新规划)
 *                     ↑              │
 *                     └──────────────┘
 *
 * 紧急状态：
 * DANGER → FLEEING → IDLE
 */
public class BehaviorStateMachine {

    public enum State {
        /** 什么都不做，等待下一次自主思考 */
        IDLE,

        /** 正在思考下一步做什么（调 LLM） */
        PLANNING,

        /** 正在执行某个动作 */
        EXECUTING,

        /** 验证动作结果 */
        VERIFYING,

        /** 紧急状态：遇到危险 */
        DANGER,

        /** 逃跑中 */
        FLEEING,

        /** 跟玩家聊天中 */
        CHATTING,

        /** 探索中 */
        EXPLORING
    }

    private State currentState = State.IDLE;
    private int stateTicks = 0;
    private int stateTimeout = 200; // 10秒超时
    private String stateContext = "";

    /**
     * 切换状态
     */
    public void transition(State newState, String context) {
        this.currentState = newState;
        this.stateTicks = 0;
        this.stateContext = context;
    }

    /**
     * 每 tick 更新
     */
    public void tick() {
        stateTicks++;

        // 超时保护：如果某个状态卡太久，回到 IDLE
        if (stateTicks > stateTimeout && currentState != State.IDLE) {
            transition(State.IDLE, "状态超时");
        }
    }

    /**
     * 是否需要调 LLM
     */
    public boolean needsThinking() {
        return currentState == State.PLANNING;
    }

    /**
     * 是否在执行动作
     */
    public boolean isExecuting() {
        return currentState == State.EXECUTING;
    }

    /**
     * 是否处于危险状态
     */
    public boolean isInDanger() {
        return currentState == State.DANGER || currentState == State.FLEEING;
    }

    /**
     * 获取给 LLM 的状态描述
     */
    public String getStateDescription() {
        return switch (currentState) {
            case IDLE -> "你在发呆，可以想想接下来做什么";
            case PLANNING -> "你在思考下一步计划";
            case EXECUTING -> "你正在执行: " + stateContext;
            case VERIFYING -> "你在检查刚才做的事结果如何";
            case DANGER -> "危险！你需要立即做出反应";
            case FLEEING -> "你在逃跑";
            case CHATTING -> "你在和别人聊天";
            case EXPLORING -> "你在探索周围";
        };
    }

    // Getters
    public State getCurrentState() { return currentState; }
    public int getStateTicks() { return stateTicks; }
    public String getStateContext() { return stateContext; }
}

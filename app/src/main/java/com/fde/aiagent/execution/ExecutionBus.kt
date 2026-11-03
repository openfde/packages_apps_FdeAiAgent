package com.fde.aiagent.execution

import java.util.concurrent.CopyOnWriteArrayList

/** 执行事件订阅者（UI / SVG 面板 / 日志都实现它）。 */
interface ExecutionListener {
    fun onEvent(event: AgentEvent)
}

/**
 * 简单的执行事件总线：
 * [SkillEngine]、[AgentCoordinator] 在任意线程 publish 事件，
 * 所有已注册的 Listener 都会收到。单个 Listener 抛异常不会中断其余 Listener。
 */
class ExecutionBus {

    private val listeners = CopyOnWriteArrayList<ExecutionListener>()

    fun addListener(listener: ExecutionListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: ExecutionListener) {
        listeners.remove(listener)
    }

    fun publish(event: AgentEvent) {
        for (listener in listeners) {
            runCatching { listener.onEvent(event) }
        }
    }
}

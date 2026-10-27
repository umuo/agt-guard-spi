package org.agentguard.streaming;

/**
 * 一轮模型响应的隔离缓冲会话，只有检查通过的内容才能交给输出回调。
 * seal 冻结上游并校验原文，完整检查通过后才调用 complete 释放剩余内容。
 */
public interface GuardStream extends AutoCloseable {
    void accept(String text);

    void seal(String authoritativeText);

    void complete(String checkedText);

    @Override
    void close();
}

package org.agentguard.streaming;

/**
 * 显式配置流式护栏的释放模式及缓存上限，长度统一使用 UTF-16 单元。
 * 完整缓存支持全量改写，窗口模式只对支持增量判断的规则提供逐段释放。
 */
public final class GuardStreamOptions {
    /**
     * 区分完整缓存检查和有限窗口检查的输出语义。
     */
    public enum Mode {
        STRICT,
        WINDOW
    }

    private final Mode mode;
    private final int chunkCharacters;
    private final long maxWaitMillis;
    private final int maxBufferedCharacters;

    private GuardStreamOptions(Mode mode, int chunkCharacters, long maxWaitMillis, int maxBufferedCharacters) {
        if (chunkCharacters < 1 || maxWaitMillis < 1 || maxWaitMillis > 60000
                || maxBufferedCharacters < chunkCharacters || maxBufferedCharacters > 16777216) {
            throw new IllegalArgumentException("Invalid streaming buffer configuration");
        }
        this.mode = mode;
        this.chunkCharacters = chunkCharacters;
        this.maxWaitMillis = maxWaitMillis;
        this.maxBufferedCharacters = maxBufferedCharacters;
    }

    public static GuardStreamOptions strict() {
        return strict(1048576);
    }

    public static GuardStreamOptions strict(int maxBufferedCharacters) {
        return new GuardStreamOptions(Mode.STRICT, 1, 50, maxBufferedCharacters);
    }

    public static GuardStreamOptions window() {
        return window(64, 50, 4096);
    }

    public static GuardStreamOptions window(int chunkCharacters, long maxWaitMillis, int maxBufferedCharacters) {
        return new GuardStreamOptions(Mode.WINDOW, chunkCharacters, maxWaitMillis, maxBufferedCharacters);
    }

    public Mode mode() {
        return mode;
    }

    public int chunkCharacters() {
        return chunkCharacters;
    }

    public long maxWaitMillis() {
        return maxWaitMillis;
    }

    public int maxBufferedCharacters() {
        return maxBufferedCharacters;
    }
}

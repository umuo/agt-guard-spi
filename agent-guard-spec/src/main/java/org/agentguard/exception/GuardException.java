package org.agentguard.exception;

/**
 * 护栏异常体系基类（Unchecked）。
 */
public class GuardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GuardException(String message) {
        super(message);
    }

    public GuardException(String message, Throwable cause) {
        super(message, cause);
    }
}

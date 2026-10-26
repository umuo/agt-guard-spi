package org.agentguard.context;

/**
 * {@link GuardContext#attributes()} 中的标准键。
 * 适配层和 Provider 对同一语义应使用同一个键，自定义键建议加业务前缀。
 */
public final class StandardAttributes {

    private StandardAttributes() {
    }

    /** 用户角色集合，值类型 {@code java.util.Set<String>}。 */
    public static final String ROLES = "agentguard.roles";

    /** 用户权限点集合，值类型 {@code java.util.Set<String>}。 */
    public static final String PERMISSIONS = "agentguard.permissions";

    /** 接入渠道（web / app / openapi ...），值类型 String。 */
    public static final String CHANNEL = "agentguard.channel";

    /** 客户端 IP，值类型 String。 */
    public static final String CLIENT_IP = "agentguard.clientIp";

    /** 语言区域，如 zh-CN，值类型 String。 */
    public static final String LOCALE = "agentguard.locale";

    /** 适配的 Agent 框架标识（langchain4j / spring-ai / custom ...），值类型 String。 */
    public static final String FRAMEWORK = "agentguard.framework";
}

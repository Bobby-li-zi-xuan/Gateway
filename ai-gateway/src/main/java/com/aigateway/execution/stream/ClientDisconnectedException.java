package com.aigateway.execution.stream;

/**
 * 客户端断开异常（脚手架，代码段 S11b）：
 * Controller 写 SseEmitter 失败（客户端断开）时抛出，
 * StreamProxy（H6）据此走"取消"路径，而不是失败降级。
 */
public class ClientDisconnectedException extends RuntimeException {
    public ClientDisconnectedException(Throwable cause) {
        super("客户端断开连接", cause);
    }
}

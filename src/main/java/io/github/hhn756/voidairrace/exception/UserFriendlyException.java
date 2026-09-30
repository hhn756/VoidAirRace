package io.github.hhn756.voidairrace.exception;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * 携带两种消息通道的异常（对齐结果类型的通道划分）：<br>
 * {@link RuntimeException#getMessage()}（构造时第一个参数）为<b>技术性消息</b>，
 * 详细，可包含服务端参数，禁止通过任何玩家可见通道发送给玩家，只能写入日志；<br>
 * {@link #getUserMessage()}为<b>用户消息</b>，显示给玩家，不详细，禁止包含技术性信息和服务端参数
 * */
public interface UserFriendlyException {
    /**
     * 获取显示给用户的异常消息，禁止包含技术性信息和服务端参数
     * */
    @Nullable Component getUserMessage();
}
package io.github.hhn756.voidairrace.addons.script.api;

import io.github.hhn756.voidairrace.addons.script.simplevalue.TextComponents;
import net.kyori.adventure.text.Component;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 服务器级操作类别（全局表{@code server}）的 Lua API 入口：{@code broadcast} /
 * {@code online_players}<br>
 * 本类别无状态，函数不依赖包实例
 * <p>
 * 本类是类别入口：装配期由{@code PluginApi}调用{@link #installInto}注入包环境
 * */
public final class ServerApi {
    /** 无状态函数对象：不持有包、句柄或状态上下文，跨包共享同一实例 */
    private static final Broadcast BROADCAST = new Broadcast();

    /** 静态工具类，不可实例化 */
    private ServerApi() {}

    /**
     * 向包脚本环境（{@code _G}）注入全局表{@code server}
     *
     * @param stateContext 共享 Lua 状态上下文（建表用）
     * @param env          包脚本环境（{@code _G}），全局表装入目标
     * */
    public static void installInto(@NonNull StateContext stateContext, @NonNull Table env) {
        Table server = stateContext.newTable();
        server.rawset(ByteString.of("broadcast"), BROADCAST);
        server.rawset(ByteString.of("online_players"), new OnlinePlayers(stateContext));
        env.rawset(ByteString.of("server"), server);
    }

    /**
     * {@code server.broadcast(message)}：向全服广播一条文本消息<br>
     * {@code message} 为字符串（= 纯文本组件快捷路径）或文本组件描述符表
     * */
    private static final class Broadcast extends AbstractFunction1 {
        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Component message = TextComponents.fromLua(arg1, "server.broadcast：第 1 个参数（消息）");
            Bukkit.getServer().broadcast(message);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code server.online_players()}：枚举在线玩家<br>
     * 返回 UUID 字符串数组（Lua 1 起始索引）
     * */
    private static final class OnlinePlayers extends AbstractFunction0 {
        private final @NonNull StateContext stateContext;

        OnlinePlayers(@NonNull StateContext stateContext) {
            this.stateContext = stateContext;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Table result = stateContext.newTable();
            long index = 1;
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.rawset(index++, ByteString.of(player.getUniqueId().toString()));
            }
            context.getReturnBuffer().setTo(result);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}

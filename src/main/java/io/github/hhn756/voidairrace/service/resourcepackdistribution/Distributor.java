package io.github.hhn756.voidairrace.service.resourcepackdistribution;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.file.GlobalSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.modules.ListenerProvider;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 资源包分发：把插件内置的资源包打成压缩包、由内置只读 HTTP 服务托管，并在玩家进入时下发
 * <p>
 * 装配期的任何一步失败都只降级为「分发未启用」并留下记录，不抛出——模块加载失败会触发全部模块回滚，
 * 而托管失败（端口被占用等）不应阻断服务器启动
 * <p>
 * 对外地址没有退化值：配置缺失或不可用即不启用。地址一旦下发，请求便由客户端发起，插件既看不到也撤不回，
 * 猜错地址的代价（向玩家暴露内网拓扑、请求打到内网中的其他设备）无法回收，因此宁可不开
 * <p>
 * 回执只用于日志与提示，不阻断游玩；提示不依赖本资源包的翻译，而是把文案作为组件的**兜底文本**随消息一起发出——
 * 这类提示恰恰在玩家拿不到本资源包时发出，翻译键在客户端解析不了，不带兜底文本就只会显示键名原文
 * */
public class Distributor implements Module, ListenerProvider, Listener {
    /**
     * 提示文本的兜底文案（与资源包语言文件中的同键文案一致）<br>
     * 语言文件是文案真相源，此处是必要的副本：兜底文案的适用场合正是客户端无法解析翻译键的场合
     * */
    private static final @NonNull String DECLINED_FALLBACK =
            "你拒绝了本服务器的资源包；正常游玩需要它。请在多人游戏列表中编辑本服务器，"
            + "把「服务器资源包」设为「询问」或「启用」，然后重进服务器\n"
            + "You declined this server's resource pack, which is required for normal play. "
            + "Please edit this server in your multiplayer list, set \"Server Resource Packs\" to "
            + "\"Prompt\" or \"Enabled\", and rejoin the server.";
    private static final @NonNull String FAILED_DOWNLOAD_FALLBACK =
            "资源包下载失败；正常游玩需要它，请检查网络后重进服务器\n"
            + "Failed to download this server's resource pack, which is required for normal play; "
            + "please check your network connection and rejoin the server.";
    private static final @NonNull String FAILED_APPLY_FALLBACK =
            "资源包未能应用；正常游玩需要它，请检查客户端设置后重进服务器\n"
            + "This server's resource pack could not be applied, and it is required for normal play; "
            + "please check your client settings and rejoin the server.";

    /** 本次启用中的分发运行态；为 null 表示分发未启用 */
    private @Nullable ActiveDistribution active;

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of(Config.class); // 装配期读全局设置，需配置管理器已就绪
    }

    @Override
    public @NonNull Collection<@NonNull Listener> getListeners() {
        return List.of(this);
    }

    /** 装配：校验对外地址与监听端口、打包、启动托管；任一步失败即降级为未启用 */
    private void onLoad() {
        YamlConfig settings = Config.getInstance().getYmlConfig(PublicFile.GLOBAL_SETTING);
        if (!settings.get(GlobalSettingKey.ENABLE_RESOURCE_PACK_DISTRIBUTION_HTTP, false)) {
            logger().fine("资源包分发未启用：配置项未开启");
            return;
        }

        URI externalAddress;
        try {
            externalAddress = parseExternalAddress(settings.get(GlobalSettingKey.RESOURCE_PACK_DISTRIBUTION_HTTP_EXTERNAL_ADDRESS));
        } catch (IllegalArgumentException e) {
            logger().warning("资源包分发未启用：" + e.getMessage());
            return;
        }

        int listenPort = settings.get(GlobalSettingKey.RESOURCE_PACK_DISTRIBUTION_HTTP_LISTEN_PORT);
        if (listenPort < 1 || listenPort > 65535) {
            logger().warning("资源包分发未启用：监听端口不在 1-65535 范围内（" + listenPort + "）");
            return;
        }

        int maxConcurrentRequests = settings.get(GlobalSettingKey.RESOURCE_PACK_DISTRIBUTION_HTTP_MAX_CONCURRENT_REQUESTS);
        if (maxConcurrentRequests < 1) {
            logger().warning("资源包分发未启用：并发请求上限须为正数（" + maxConcurrentRequests + "）");
            return;
        }

        PackArchive.Packed packed;
        try {
            packed = PackArchive.pack();
        } catch (IOException e) {
            logger().log(Level.WARNING, "资源包分发未启用：打包插件内置资源包失败", e);
            return;
        }

        PackServer server;
        try {
            server = PackServer.start(
                    listenPort, pathOf(externalAddress), packed.content(), maxConcurrentRequests);
        } catch (IOException | SecurityException e) {
            logger().log(Level.WARNING, "资源包分发未启用：在端口 " + listenPort + " 上启动托管失败", e);
            return;
        }

        active = new ActiveDistribution(
                server,
                ResourcePackInfo.resourcePackInfo(packed.id(), externalAddress, packed.hash())
        );
        logger().info("资源包分发已启用：对外地址 " + externalAddress
                + "，监听端口 " + listenPort
                + "，并发请求上限 " + maxConcurrentRequests
                + "，摘要 " + packed.hash());
    }

    /** 卸载：停止托管并收束线程；分发未启用时为无操作 */
    private void onUnload() {
        ActiveDistribution current = active;
        active = null;
        if (current != null) current.server().stop();
    }

    /**
     * 玩家进入时下发本次装配结果；摘要与包标识都由内容派生，玩家重进时命中客户端缓存而不会重复下载
     * */
    @EventHandler
    public void onPlayerJoin(@NonNull PlayerJoinEvent event) {
        ActiveDistribution current = active;
        if (current == null) return;

        event.getPlayer().sendResourcePacks(
                ResourcePackRequest.resourcePackRequest()
                        .packs(current.info())
                        .required(false)
                        .callback(this::onStatus)
                        .build()
        );
    }

    /**
     * 消费玩家侧回执：中间状态（已接受、已下载）不处理，终态记日志，并在玩家拿不到自定义文本时给一句提示<br>
     * 「应用成功」是唯一能确认玩家真正拥有该资源包的结果，因此其余终态都意味着该玩家看不到自定义文本
     * */
    private void onStatus(@NonNull UUID id, @NonNull ResourcePackStatus status, @NonNull Audience audience) {
        if (status.intermediate()) return;

        String who = audience instanceof Player player ? player.getName() : "非玩家听众";
        switch (status) {
            case SUCCESSFULLY_LOADED -> logger().fine("玩家 " + who + " 已应用资源包");
            case DECLINED -> {
                logger().warning("玩家 " + who + " 拒绝了资源包，其自定义文本将不可见");
                audience.sendMessage(Component.translatable(TranslateKeys.ResourcePack.DECLINED, DECLINED_FALLBACK));
            }
            case FAILED_DOWNLOAD -> {
                logger().warning("玩家 " + who + " 的资源包下载失败");
                audience.sendMessage(Component.translatable(
                        TranslateKeys.ResourcePack.FAILED_DOWNLOAD, FAILED_DOWNLOAD_FALLBACK));
            }
            default -> {
                logger().warning("玩家 " + who + " 的资源包未能应用：" + status);
                audience.sendMessage(Component.translatable(
                        TranslateKeys.ResourcePack.FAILED_APPLY, FAILED_APPLY_FALLBACK));
            }
        }
    }

    /**
     * 解析对外地址：只接受带主机的明文 http 完整地址，其余情形一律判为不可用
     * <p>
     * 无退化值的理由见类注释；本方法不读取监听地址，也不尝试从本机地址中挑选主机
     * */
    private static @NonNull URI parseExternalAddress(@Nullable String configured) {
        if (configured == null || configured.isBlank())
            throw new IllegalArgumentException("对外地址未配置（不做退化，须显式填写客户端可访问的完整 URL）");

        URI address;
        try {
            address = new URI(configured.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("对外地址不是合法 URL：" + configured);
        }

        if (!"http".equals(address.getScheme()))
            throw new IllegalArgumentException("对外地址须为明文 http 地址（当前为 " + address.getScheme() + "）"
                    + "；加密托管需自备证书，本期不提供：" + configured);
        if (address.getHost() == null)
            throw new IllegalArgumentException("对外地址缺少主机名：" + configured);
        if (address.getQuery() != null || address.getFragment() != null)
            throw new IllegalArgumentException("对外地址不得携带查询串或片段：" + configured);
        return address;
    }

    /** 对外地址的路径即托管路径：客户端请求的就是这个地址，服务侧只在该路径上提供内容；未写路径时按根路径处理 */
    private static @NonNull String pathOf(@NonNull URI address) {
        return address.getPath().isEmpty() ? "/" : address.getPath();
    }

    private static @NonNull Logger logger() {
        return VoidAirRace.getInstance().getLogger();
    }

    /**
     * 分发运行态
     *
     * @param server 托管服务
     * @param info 下发要素（包标识、对外地址、内容摘要）
     * */
    private record ActiveDistribution(
            @NonNull PackServer server,
            @NonNull ResourcePackInfo info
    ) {
    }
}

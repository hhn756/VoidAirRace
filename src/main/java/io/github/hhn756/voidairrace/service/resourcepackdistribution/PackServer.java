package io.github.hhn756.voidairrace.service.resourcepackdistribution;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 把一份资源包压缩包固定托管在某个对外路径上的只读 HTTP 服务
 * <p>
 * 暴露面：只服务这一个路径上的这一份内容——路径不匹配一律 404，非读取类请求一律 405，
 * 不提供目录列表、不接受路径参数、不读取任意文件、不回显请求内容，响应头也不携带实现与版本细节；
 * 内容本身是玩家可见的公开资源，因此明文传输即可，传输损坏与缓存错配由摘要兜底
 * <p>
 * 绑定通配地址（全部网卡）：对外可见的主机由配置的对外地址描述，服务侧不自行挑选本机地址。
 * 并发连接数受限（水位由配置给出），避免内容被当作流量放大点；线程为守护线程并在 {@link #stop()} 时收束
 * */
public final class PackServer {
    /** 等待队列长度 */
    private static final int BACKLOG = 16;
    private static final @NonNull String CONTENT_TYPE = "application/zip";
    private static final @NonNull String READ_METHODS = "GET, HEAD";

    private final @NonNull HttpServer server;
    private final @NonNull ExecutorService executor;
    private final @NonNull String path;
    private final byte @NonNull [] content;

    private PackServer(
            @NonNull HttpServer server,
            @NonNull ExecutorService executor,
            @NonNull String path,
            byte @NonNull [] content
    ) {
        this.server = server;
        this.executor = executor;
        this.path = path;
        this.content = content;
    }

    /**
     * 在全部网卡的指定端口上启动托管
     *
     * @param port 监听端口
     * @param path 对外路径，须以 '/' 开头
     * @param content 要托管的压缩包内容
     * @param maxConcurrentRequests 并发处理的请求数上限，须为正数
     *
     * @return 已启动的服务实例
     *
     * @throws IOException 端口不可用或启动失败时抛出
     * @throws IllegalArgumentException 路径不合法或并发上限不是正数时抛出
     * */
    public static @NonNull PackServer start(
            int port,
            @NonNull String path,
            byte @NonNull [] content,
            int maxConcurrentRequests
    ) throws IOException {
        if (!path.startsWith("/")) throw new IllegalArgumentException("对外路径必须以 '/' 开头：" + path);
        if (maxConcurrentRequests < 1)
            throw new IllegalArgumentException("并发请求上限必须为正数：" + maxConcurrentRequests);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), BACKLOG);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "Voidairrace-resource-pack-http");
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrentRequests, threadFactory);
        PackServer instance = new PackServer(server, executor, path, content);

        server.setExecutor(executor);
        server.createContext(path, instance::handle);
        server.start();
        return instance;
    }

    /**
     * 停止托管并收束线程池；插件卸载时必须调用，否则线程与端口会泄漏到下一次启用
     * */
    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(@NonNull HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            boolean readOnly = method.equals("GET") || method.equals("HEAD");
            if (!readOnly) {
                exchange.getResponseHeaders().set("Allow", READ_METHODS);
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // createContext 按前缀匹配，此处再按精确路径收敛，杜绝把其余路径也纳进来
            if (!exchange.getRequestURI().getPath().equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(content.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(content);
            }
        } catch (IOException ignored) {
            // 连接中断等客户端侧原因：响应已无法送达，无补救动作
        } finally {
            exchange.close();
        }
    }
}

package io.github.hhn756.voidairrace.service.resourcepackdistribution;

import io.github.hhn756.voidairrace.constants.ResourcePath;
import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件内置资源包的压缩包与内容摘要
 * <p>
 * 摘要有三个用途：客户端缓存判定、完整性校验、派生出包标识。因此压缩包必须是<b>确定性产物</b>——
 * 同一份资源在任何一次插件启用中都产出相同字节，条目顺序与时间戳都被固定；否则每次启动摘要都会变，
 * 客户端会认定这是另一个包而重复下载
 * <p>
 * 压缩包整体驻留内存：内容只有插件自有资源、体积可控，换取托管阶段无需磁盘落地与清理
 * */
public final class PackArchive {
    /**
     * 压缩包内条目的固定时间戳（1980-01-01T00:00:00Z，ZIP 的 DOS 时间下限）<br>
     * 注：DOS 时间按本地时区编码，因此「相同内容产出相同字节」只在同一部署环境内成立，这对客户端缓存已经足够
     * */
    private static final long FIXED_ENTRY_TIME = 315_532_800_000L;

    private PackArchive() {
    }

    /**
     * 已打包的资源包
     *
     * @param content 压缩包字节
     * @param hash 压缩包内容的 SHA-1 摘要（40 位小写十六进制，即线上协议携带的形式）
     * @param id 由摘要派生的包标识：内容不变则标识不变，客户端因而把它认作同一个包
     * */
    public record Packed(
            byte @NonNull [] content,
            @NonNull String hash,
            @NonNull UUID id
    ) {}

    /**
     * 读取插件内置资源包目录、打成压缩包并计算摘要
     *
     * @return 压缩包字节、摘要与包标识
     *
     * @throws IOException 资源包目录不存在或读取失败时抛出
     * */
    public static @NonNull Packed pack() throws IOException {
        URL root = PackArchive.class.getResource(ResourcePath.RESOURCEPACK.toString());
        if (root == null) throw new IOException("插件内置资源包目录不存在：" + ResourcePath.RESOURCEPACK);

        // 打包产物随插件以 jar 分发，也允许开发期以类目录形式存在
        byte[] content = root.getProtocol().equals("jar") ? packFromJar(root) : packFromDirectory(root);
        byte[] digest = sha1(content);
        return new Packed(content, hex(digest), UUID.nameUUIDFromBytes(digest));
    }

    /**
     * 从插件 jar 中打包：jar 地址形如 {@code jar:file:/path/plugin.jar!/voidairrace/resourcepack/}，
     * 取出其中的 jar 文件地址后按 {@link JarFile} 读条目
     * <p>
     * 不用 zip 文件系统：插件 jar 已被引导阶段的数据包发现挂载，按同一地址再次挂载会抛
     * {@link java.nio.file.FileSystemAlreadyExistsException}，而取用他人已挂载的文件系统又会把它的生命周期牵连进来
     * */
    private static byte[] packFromJar(@NonNull URL root) throws IOException {
        String external = root.toExternalForm();
        int entrySeparator = external.indexOf("!/");
        if (!external.startsWith("jar:") || entrySeparator < 0)
            throw new IOException("无法识别的资源包目录地址：" + external);

        Path jarPath;
        try {
            jarPath = Paths.get(URI.create(external.substring("jar:".length(), entrySeparator)));
        } catch (RuntimeException e) {
            throw new IOException("无法识别的插件 jar 地址：" + external, e);
        }
        String prefix = ResourcePath.RESOURCEPACK.toString().substring(1); // 条目名前缀不带前导 '/'

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<JarEntry> entries = new ArrayList<>();
            jar.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
                    .forEach(entries::add);
            entries.sort(Comparator.comparing(JarEntry::getName));

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                for (JarEntry entry : entries) {
                    putNext(zip, entry.getName().substring(prefix.length()), jar.getInputStream(entry));
                }
            }
            return buffer.toByteArray();
        }
    }

    /**
     * 从类目录中打包（开发期情形）：条目名为 '/'-分隔的相对路径，顺序按条目名排序
     * */
    private static byte[] packFromDirectory(@NonNull URL root) throws IOException {
        Path base;
        try {
            base = Paths.get(root.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("无法识别的资源包目录地址：" + root, e);
        }
        if (!Files.isDirectory(base)) throw new IOException("资源包目录不存在：" + base);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing((Path file) -> entryName(base, file)));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Path file : files) putNext(zip, entryName(base, file), Files.newInputStream(file));
        }
        return buffer.toByteArray();
    }

    /**
     * 写入一个压缩包条目；时间戳固定、不写入目录条目
     * */
    private static void putNext(
            @NonNull ZipOutputStream zip,
            @NonNull String name,
            @NonNull InputStream source
    ) throws IOException {
        try (source) {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(FIXED_ENTRY_TIME);
            zip.putNextEntry(entry);
            source.transferTo(zip);
            zip.closeEntry();
        }
    }

    private static @NonNull String entryName(@NonNull Path base, @NonNull Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    private static @NonNull byte[] sha1(byte @NonNull [] content) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1").digest(content);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 是协议规定的摘要算法，缺失即运行环境不完整，无法继续
            throw new IOException("当前运行时缺少 SHA-1 摘要算法", e);
        }
    }

    private static @NonNull String hex(byte @NonNull [] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}

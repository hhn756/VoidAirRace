package io.github.hhn756.voidairrace.infrastructure.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarEntryUtil {
    /**
     * 从当前 JAR 复制指定文件或目录到目标系统路径
     *
     * @param sourcePath JAR 内的源路径（例如 {@code /config} 或 {@code data/file.txt}），
     *                   开头可带或不带 {@code /}，均视为相对于 JAR 根目录
     * @param targetPath 目标文件系统路径（绝对或相对路径）
     * @throws IOException 如果复制过程中发生 I/O 错误
     */
    public static void copyFromJar(String sourcePath, String targetPath) throws IOException {
        // 规范化源路径：去掉开头斜杠，末尾不加斜杠（用于匹配）
        String normalizedSource = sourcePath.replace('\\', '/');
        if (normalizedSource.startsWith("/")) {
            normalizedSource = normalizedSource.substring(1);
        }
        if (normalizedSource.endsWith("/")) {
            normalizedSource = normalizedSource.substring(0, normalizedSource.length() - 1);
        }

        // 获取当前 JAR 文件的路径
        URL jarUrl = JarEntryUtil.class.getProtectionDomain().getCodeSource().getLocation();
        File jarFile;
        try {
            jarFile = new File(jarUrl.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("无法解析 JAR 文件路径", e);
        }

        // 如果当前运行环境不是 JAR（例如 IDE 中运行），则尝试从类路径目录复制
        if (!jarFile.isFile()) {
            copyFromDirectory(jarFile.getPath(), normalizedSource, targetPath);
            return;
        }

        // 打开 JAR 文件并遍历条目
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            Path targetRoot = Paths.get(targetPath).normalize();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                String relativePath = relativePathIfUnder(normalizedSource, entry.getName());
                if (relativePath == null) continue;

                // 构建目标路径
                Path targetFile = targetRoot.resolve(relativePath).normalize();

                if (entry.isDirectory()) {
                    // 创建目录
                    Files.createDirectories(targetFile);
                } else {
                    // 创建父目录
                    Files.createDirectories(targetFile.getParent());
                    // 复制文件
                    try (InputStream is = jar.getInputStream(entry)) {
                        Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * 判断一个 JAR 条目是否位于源路径之下（条目本身即源路径时也算），并计算相对源路径的路径
     *
     * @param normalizedSource 规范化后的源路径（不含开头与结尾斜杠；空串表示整个 JAR）
     * @param entryName JAR 条目名
     *
     * @return 相对路径；条目本身即源路径时返回空串；不位于源路径之下时返回{@code null}
     * */
    private static String relativePathIfUnder(String normalizedSource, String entryName) {
        if (normalizedSource.isEmpty()) return entryName;
        if (entryName.equals(normalizedSource)) return "";
        if (entryName.startsWith(normalizedSource + "/")) {
            return entryName.substring(normalizedSource.length() + 1);
        }
        return null;
    }

    /**
     * 当运行环境为目录（而非 JAR）时的回退复制方法
     */
    private static void copyFromDirectory(String classPathRoot, String sourcePath, String targetPath) throws IOException {
        Path sourceDir = Paths.get(classPathRoot).resolve(sourcePath).normalize();
        Path targetDir = Paths.get(targetPath).normalize();

        if (!Files.exists(sourceDir)) {
            throw new IOException("源路径不存在: " + sourceDir);
        }

        // 使用 Files.walk 递归复制
        Files.walk(sourceDir).forEach(source -> {
            Path relative = sourceDir.relativize(source);
            Path target = targetDir.resolve(relative);
            try {
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("复制失败: " + source, e);
            }
        });
    }
}

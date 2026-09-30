package io.github.hhn756.voidairrace.infrastructure.util;

import io.github.hhn756.voidairrace.VoidAirRace;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 插件类扫描器，用于扫描指定插件内指定类型的所有子类
 * <p>
 * 优先读取编译期生成的子类索引（Gradle 任务 generateSubclassIndex 生成、随 jar 打包，实现见 buildSrc 的 SubclassIndexTask）<br>
 * 索引缺失或未覆盖目标父类型（如用户包新增子类）时，回退为运行时全量扫描，行为语义兼容
 * */
public class ClassScanner {
    private ClassScanner() {}

    /**
     * 编译期子类索引的资源路径，与 buildSrc SubclassIndexTask 的输出位置保持一致
     * */
    public static final String INDEX_RESOURCE = "META-INF/voidairrace/subclass-index.txt";

    /**
     * 读索引固定使用的类加载器：插件主类的类加载器（索引与插件主类随同一 jar）
     * */
    private static final ClassLoader PLUGIN_LOADER = VoidAirRace.class.getClassLoader();

    /**
     * 已解析的索引缓存<br>
     * null 表示尚未解析；空 Map 为“确认无索引”的负缓存
     * */
    private static @Nullable Map<String, List<String>> cachedIndex;

    /**
     * 扫描插件中所有指定类型的子类（包括实现类），使用插件主类的包作为根包（扫描起点）
     *
     * @param type 要查找的父类型（类或接口）
     * @param <T> 泛型类型
     *
     * @return 符合条件的子类列表（不包含 type 本身）
     * */
    public static <T> Collection<Class<T>> scanSubclasses(Class<T> type) {
        return scanSubclasses(type, VoidAirRace.class.getPackage().getName());
    }

    /**
     * 扫描插件中指定包及其子包下所有指定类型的子类（包括实现类）
     * <p>
     * 优先尝试插件的编译期索引（索引与插件类随同一 jar，类加载仍使用传入的 classLoader）<br>
     * 索引未覆盖该父类型时回退为扫描指定代码源。用于 Bootstrap 及用户包等显式指定代码源的场景
     *
     * @param type 要查找的父类型（类或接口）
     * @param basePackage 要扫描的基础包名（例如 "com.abc.project"）<br>
     *                    若为 null 或空字符串，则回退到扫描插件主类的包
     * @param <T> 泛型类型
     *
     * @return 符合条件的子类列表（不包含 type 本身）
     * */
    public static <T> Collection<Class<T>> scanSubclasses(Class<T> type, String basePackage) {
        // 如果未指定包名，则使用插件主类的包
        if (basePackage == null || basePackage.trim().isEmpty()) {
            basePackage = VoidAirRace.class.getPackage().getName();
        }

        // 1. 编译期索引
        Map<String, List<String>> index = indexOf();
        if (index != null) {
            Collection<Class<T>> result = loadFromIndex(PLUGIN_LOADER, index, type, basePackage);
            if (result != null) return result;
        }

        // 2. 回退：运行时扫描插件代码源（jar 文件或编译输出目录）
        try {
            URL location = VoidAirRace.class.getProtectionDomain().getCodeSource().getLocation();
            return runtimeScan(PLUGIN_LOADER, location, type, basePackage);
        } catch (Exception e) {
            VoidAirRace.getInstance().getLogger().log(Level.SEVERE, "扫描插件类失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 扫描指定代码源（目录或 JAR 文件）中指定包下的所有类，并返回给定类型的非抽象子类型列表
     * <p>
     * 优先尝试插件的编译期索引（索引与插件类随同一 jar，类加载仍使用传入的 classLoader）<br>
     * 索引未覆盖该父类型时回退为扫描指定代码源。用于 Bootstrap 及用户包等显式指定代码源的场景
     *
     * @param <T> 目标类型，用于限定返回的 Class 对象必须是其子类型
     * @param classLoader 用于加载找到的类的类加载器
     * @param codeSourceUrl 代码源的位置，必须是有效的 {@code file:} URL，指向一个目录或 JAR 文件
     * @param type 要查找的子类型的父类型（接口或类）
     * @param basePackage 要扫描的基础包名，例如 "com.example"；包名中的点将自动转换为文件系统路径分隔符
     *
     * @return 所有满足条件的子类型的 Class 对象列表；如果扫描过程出错或未找到任何匹配类，则返回空列表（非 {@code null}）
     *
     * @see java.lang.ClassLoader#loadClass(String)
     * @see java.net.URL
     * @see java.util.jar.JarFile
     * */
    public static <T> Collection<Class<T>> scanSubclasses(ClassLoader classLoader, URL codeSourceUrl,
                                                          Class<T> type, String basePackage) {
        Map<String, List<String>> index = indexOf();
        if (index != null) {
            Collection<Class<T>> result = loadFromIndex(classLoader, index, type, basePackage);
            if (result != null) return result;
        }
        return runtimeScan(classLoader, codeSourceUrl, type, basePackage);
    }

    // ------ 内部实现 -----

    /**
     * 读取并解析编译期索引（只解析一次，“确认无索引”的负结果同样缓存）
     *
     * @return 父类型FQN -> 子孙类FQN列表；无索引资源时返回 null
     * */
    private static @Nullable Map<String, List<String>> indexOf() {
        synchronized (ClassScanner.class) {
            if (cachedIndex != null) {
                return cachedIndex.isEmpty() ? null : cachedIndex;
            }

            Map<String, List<String>> parsed = null;
            try {
                URL resource = PLUGIN_LOADER.getResource(INDEX_RESOURCE);
                if (resource != null) {
                    Map<String, List<String>> groups = new HashMap<>();
                    try (InputStream in = resource.openStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isEmpty() || line.charAt(0) == '#') continue;
                            String[] parts = line.split(" ");
                            if (parts.length < 2) continue; // 只有键没有子孙，视为无效行
                            groups.put(parts[0], List.of(Arrays.copyOfRange(parts, 1, parts.length)));
                        }
                    }
                    parsed = groups;
                }
            } catch (Exception ignored) {
            }
            cachedIndex = parsed != null ? parsed : Collections.emptyMap();
            return parsed;
        }
    }

    /**
     * 从索引加载指定父类型的子孙类，语义对齐运行时扫描<br>
     * 按 basePackage 过滤、排除父类型本身、loadClass 后 isAssignableFrom 最终校验
     *
     * @return 命中（含命中空组）返回不可变列表；索引无该父类型的键时返回 null，由调用方回退扫描
     * */
    @SuppressWarnings("unchecked")
    private static @Nullable <T> Collection<Class<T>> loadFromIndex(ClassLoader classLoader, Map<String, List<String>> index,
                                                                    Class<T> type, String basePackage) {
        List<String> classNames = index.get(type.getName());
        if (classNames == null) return null; // 索引未覆盖该父类型（如用户包新增子类），回退扫描

        List<Class<T>> result = new ArrayList<>();
        for (String className : classNames) {
            if (!isInPackage(className, basePackage)) continue;
            try {
                Class<?> clazz = classLoader.loadClass(className);
                if (type.isAssignableFrom(clazz) && !clazz.equals(type)) {
                    result.add((Class<T>) clazz);
                }
            } catch (Throwable t) {
                // 单个类加载失败不阻断其余（链接错误一并容错）
                VoidAirRace.getInstance().getLogger().warning("无法加载索引中的类: " + className);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 类名是否落在指定包（或其子包）下
     * */
    private static boolean isInPackage(String className, String basePackage) {
        if (basePackage == null || basePackage.isEmpty()) return true;
        return className.startsWith(basePackage + ".");
    }

    /**
     * 运行时全量扫描：枚举代码源内类名 -> 加载 -> 验亲缘
     *
     * @return 满足条件的子类型列表；扫描过程出错时返回空列表
     * */
    @SuppressWarnings("unchecked")
    private static <T> Collection<Class<T>> runtimeScan(ClassLoader classLoader, URL codeSourceUrl,
                                                        Class<T> type, String basePackage) {
        List<String> classNames = new ArrayList<>();
        try {
            File source = new File(codeSourceUrl.toURI());
            String basePath = basePackage.replace('.', File.separatorChar);
            String jarBasePath = basePackage.replace('.', '/');

            if (source.isDirectory()) {
                File baseDir = new File(source, basePath);
                if (baseDir.exists() && baseDir.isDirectory()) {
                    collectClassNamesInDirectory(baseDir, basePackage, classNames);
                }
            } else {
                try (JarFile jar = new JarFile(source)) {
                    collectClassNamesInJar(jar, jarBasePath, classNames);
                }
            }

            List<Class<T>> result = new ArrayList<>();
            for (String className : classNames) {
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    if (type.isAssignableFrom(clazz) && !clazz.equals(type)) {
                        result.add((Class<T>) clazz);
                    }
                } catch (ClassNotFoundException e) {
                    // 忽略
                }
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            Logger logger = VoidAirRace.getInstance().getLogger();
            logger.log(Level.SEVERE, "运行时扫描类失败", e);
            // 忽略异常，返回空列表
            return Collections.emptyList();
        }
    }

    /**
     * 递归扫描目录，收集所有 .class 文件的完整类名
     * */
    private static void collectClassNamesInDirectory(File dir, String packageName, List<String> classNames) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collectClassNamesInDirectory(file, packageName + "." + file.getName(), classNames);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                classNames.add(className);
            }
        }
    }

    /**
     * 扫描 jar 文件，收集所有 .class 文件的完整类名
     * */
    private static void collectClassNamesInJar(JarFile jar, String jarBasePath, List<String> classNames) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            // 检查条目是否在目标包路径下，并且是 .class 文件
            if (name.startsWith(jarBasePath) && name.endsWith(".class")) {
                // 确保是直接子包或更深层包中的类（排除其他不相关路径）
                // 但 names.startsWith(jarBasePath) 已经足够，因为 jar 路径是唯一前缀
                String className = name.replace('/', '.').replace(".class", "");
                classNames.add(className);
            }
        }
    }
}

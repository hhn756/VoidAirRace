package io.github.hhn756.voidairrace.core.addons.usrpackage.script.api;

import io.github.hhn756.voidairrace.core.addons.usrpackage.UsrPackage;
import net.sandius.rembulan.LuaRuntimeException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 用户包资源访问的统一入口：路径语法白名单解析 + 跨包访问的依赖白名单裁定<br>
 * 供各 Lua API 共用——现有的 {@code read_asset} / {@code list_assets} / {@code require}
 * 与后续新增的资源类 API（配置、音频等）都应以本类为唯一访问通道
 * <p>
 * <strong>路径语法是白名单</strong>：仅接受「包内相对路径段」以{@code /}分隔的形式，
 * 段字符为小写字母、数字、下划线、连字符，可携带点分扩展名（点不可居段首）。
 * 该语法在书写层面不存在{@code ..}、反斜杠、冒号（包 Id 分隔符除外）、绝对路径等
 * 任何越出包根的形式，因此解析只需一次正则匹配，<strong>不含任何越狱防护代码</strong>，
 * {@link Path#resolve(Path) Path.resolve} 的结果必然位于包根之内
 * <p>
 * <strong>跨包访问统一规则</strong>：{@code pkgid:path} 形式指定其他包时，目标包必须是调用方
 * <em>直接声明</em>的依赖且已加载——引用谁的资源就声明依赖谁（不含传递闭包）；
 * 自身包既可省略前缀也可写全名
 * <p>
 * 残余边界（有意不管辖，注明以免误估）：
 * 目录形式包内的符号链接（zip 形态不存在符号链接；目录包由能写服务器磁盘的一方放置，
 * 已在插件信任边界之外）；{@code list_assets} 的输出条目名不过滤（仅作展示输出，
 * 以其再次访问时仍须经本类解析，不合语法的名字自然不可达）
 * */
public final class PackageAccess {

    private PackageAccess() {}

    /**
     * 单段形态：小写字母/数字/下划线/连字符组成的名字，可携带点分扩展名
     * （点不可居段首，故{@code ..}无法成段）
     * */
    private static final String SEGMENT = "[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*";

    /**
     * 包内相对路径：段以{@code /}分隔<br>
     * 匹配即安全——不存在越出包根的书写形式，解析阶段无需任何防护代码
     * */
    private static final Pattern REL_PATH = Pattern.compile(SEGMENT + "(?:/" + SEGMENT + ")*");

    /** 包 Id 形态（与元素键名同等从宽字符集，经 NamespacedKey 语义约束小写） */
    private static final Pattern PKG_ID = Pattern.compile("[a-z0-9_-]+");

    /** 单文件读取上限（字节）：所有经本类读取的文件统一执行，防超大文件顶爆内存 */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    /**
     * 资源访问目标：所属包 + 相对包根的路径（已通过语法白名单）<br>
     * 调用方以{@code pkg.resolve(子目录).resolve(relPath())}定位实际文件，
     * 子目录拼接不破坏安全性（relPath 在语法上不可能越出）
     * */
    public record Target(@NonNull UsrPackage pkg, @NonNull String relPath) {}

    /**
     * 解析资源访问目标<br>
     * {@code spec} 为{@code path}（本包）或{@code pkgid:path}（跨包，目标包须为调用方直接声明的依赖且已加载）；
     * 包 Id 非法、路径语法非法或跨包越权即抛 Lua 错误
     *
     * @param caller   发起访问的包（决定依赖白名单）
     * @param spec     原始路径串（含或不含{@code pkgid:}前缀）
     * @param resolver 包 Id → 已加载包对象的解析函数，查无此包返回{@code null}
     *
     * @return 访问目标（所属包 + 已通过白名单的相对路径）
     * */
    public static @NonNull Target resolve(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        UsrPackage target;
        String relPath;
        int separator = spec.indexOf(':');
        if (separator < 0) {
            // 无前缀 = 访问本包
            target = caller;
            relPath = spec;
        } else {
            String pkgId = spec.substring(0, separator);
            relPath = spec.substring(separator + 1);
            if (!PKG_ID.matcher(pkgId).matches()) {
                throw new LuaRuntimeException("资源路径的包 Id 非法：" + spec);
            }
            if (pkgId.equals(caller.id())) {
                target = caller;
            } else {
                // 跨包：引用谁的资源就声明依赖谁的包（直接依赖，不含传递闭包）
                if (!caller.dependencies().contains(pkgId)) {
                    throw new LuaRuntimeException("资源访问的目标包未声明为依赖：" + spec
                            + "（允许的范围：" + caller.dependencies() + "）");
                }
                target = resolver.apply(pkgId);
                if (target == null) {
                    throw new LuaRuntimeException("资源访问的目标包未加载：" + spec);
                }
            }
        }
        if (!REL_PATH.matcher(relPath).matches()) {
            throw new LuaRuntimeException("资源路径语法非法（只允许小写字母、数字、下划线、连字符、"
                    + "点分扩展名，以 / 分隔）：" + spec);
        }
        return new Target(target, relPath);
    }

    /**
     * 读取一个文件的全部字节，超过上限抛 Lua 错误<br>
     * 文件路径须由调用方经{@link #resolve}定位（本方法不重复校验来源）
     *
     * @param file     已定位的文件路径
     * @param describe 文件的资源路径描述（原始 spec），仅用于错误消息
     * @param maxBytes 大小上限（字节）
     *
     * @return 文件全部字节
     * */
    public static byte[] readBytes(@NonNull Path file, @NonNull String describe, long maxBytes) {
        try {
            long size = Files.size(file);
            if (size > maxBytes) {
                throw new LuaRuntimeException("资源文件超过大小上限（" + maxBytes + " 字节）：" + describe);
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new LuaRuntimeException("读取资源文件失败：" + describe + "（" + e.getMessage() + "）");
        }
    }
}

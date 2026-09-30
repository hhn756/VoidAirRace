package voidairrace.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import java.util.TreeMap

/**
 * 全量编译期子类索引生成任务
 *
 * 读取主源码集全部 .class 文件的类声明头（仅常量池与继承边，不解析代码，不加载类），
 * 沿 super/interfaces 链求传递闭包，输出“父类型 -> 全部子孙类”索引资源，
 * 经 `sourceSets.main.resources.srcDir(...)` 随 processResources 打进插件 jar。
 *
 * 设计要点：
 * - 通用全量：任务不感知任何扫描需求（不读 ClassScanner 调用点、无父类型白名单）。
 *   插件类的全部祖先都作为索引 key，包括 Bukkit 等外部父类型（子孙端始终是插件类）。
 * - 外部类型链截断：若祖先关系藏在非本项目类层级里（如 插件类 -> 外部抽象类 -> 外部接口），
 *   截断点之后的祖先不会成键；运行时查不到键会回退全量扫描，正确性不受影响。
 * - 增量：@InputFiles 挂 compileJava 输出，@OutputDirectory 落 build/generated。
 *
 * 索引路径与 ClassScanner.INDEX_RESOURCE 保持一致：META-INF/voidairrace/subclass-index.txt
 * 格式：每行 `父类型FQN 子1FQN 子2FQN ...`（FQN 用点分隔；嵌套类为 A$B，可直接 loadClass），
 * 键与组内值均排序，输出确定性。
 */
abstract class SubclassIndexTask : DefaultTask() {

    /** 要解析的 class 文件目录集（通常接 sourceSets.main.output.classesDirs） */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    /** 索引输出根目录，任务在其下写 META-INF/voidairrace/subclass-index.txt */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        // internal name（如 a/b/C）-> (super, interfaces)；仅收录本项目可见类
        val edges = HashMap<String, Pair<String?, List<String>>>()
        classesDirs.asFileTree.matching { include("**/*.class") }.forEach { file ->
            val reader = ClassReader(file.readBytes())
            // 跳过编译器合成类（lambda、switch 映射表等），它们不可能被手动注册
            if (reader.access and Opcodes.ACC_SYNTHETIC != 0) return@forEach
            val owner = reader.className
            if (owner == "module-info" || owner.endsWith("/module-info") ||
                owner.endsWith("/package-info")
            ) return@forEach
            edges[owner] = (reader.superName?.takeUnless { it == OBJECT }) to
                    reader.interfaces.filter { it != OBJECT }
        }

        // 传递闭包 + memo；visiting 集合防御非法类层级导致的环
        val ancestorsMemo = HashMap<String, Set<String>>()
        val visiting = HashSet<String>()
        fun ancestors(owner: String): Set<String> {
            ancestorsMemo[owner]?.let { return it }
            if (!visiting.add(owner)) return emptySet()
            val result = LinkedHashSet<String>()
            edges[owner]?.let { (superName, interfaces) ->
                (listOfNotNull(superName) + interfaces).forEach { parent ->
                    result.add(parent)
                    result.addAll(ancestors(parent))
                }
            }
            visiting.remove(owner)
            ancestorsMemo[owner] = result
            return result
        }

        // 父类型FQN -> 子孙类FQN列表（TreeMap + 组内排序保证确定性）
        val groups = TreeMap<String, MutableList<String>>()
        for ((owner, _) in edges) {
            val dottedOwner = owner.replace('/', '.')
            for (ancestor in ancestors(owner)) {
                groups.getOrPut(ancestor.replace('/', '.')) { ArrayList() }.add(dottedOwner)
            }
        }

        val outFile = outputDir.get().asFile
            .resolve("META-INF").resolve("voidairrace").resolve("subclass-index.txt")
        outFile.parentFile.mkdirs()
        val sb = StringBuilder()
        sb.appendLine("# 由 generateSubclassIndex 任务生成，请勿手动编辑。")
        sb.appendLine("# 格式：每行 = 父类型FQN + 空格分隔的子孙类FQN列表；键与值均字典序。")
        for ((parent, descendants) in groups) {
            sb.append(parent)
            for (descendant in descendants.sorted()) sb.append(' ').append(descendant)
            sb.append('\n')
        }
        outFile.writeText(sb.toString(), Charsets.UTF_8)

        val relationCount = groups.values.sumOf { it.size }
        logger.lifecycle("子类索引：${groups.size} 个父类型、$relationCount 条继承关系 -> ${outFile.path}")
    }

    companion object {
        /** Object 无注册价值，且会把所有类聚到同一组，直接剔除 */
        private const val OBJECT = "java/lang/Object"
    }
}

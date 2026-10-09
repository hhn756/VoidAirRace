import com.sun.source.tree.*
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import voidairrace.build.SubclassIndexTask
import java.nio.file.Path
import java.util.*
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

group = "io.github.hhn756"
version = "0.1"

plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    mavenCentral()
}

dependencies {
    // paper（paper服务端）
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT") // paper开发包
    compileOnly("net.kyori:adventure-api:4.26.1") // 服务端自带这个库

    // rembulan（lua库）
    implementation(files("libs/rembulan/rembulan-compiler-0.4.2.jar"))
    implementation(files("libs/rembulan/rembulan-runtime-0.4.2.jar"))
    implementation(files("libs/rembulan/rembulan-stdlib-0.4.2.jar"))
    // ASM（字节码生成库）—— rembulan-compiler 编译 Lua 时运行期需要（缺它时编译能过、运行报 NoClassDefFoundError）
    implementation("org.ow2.asm:asm:6.2")
    implementation("org.ow2.asm:asm-tree:6.2")
    implementation("org.ow2.asm:asm-analysis:6.2")
    implementation("org.ow2.asm:asm-util:6.2")
}

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(7, "days") // 缓存快照7天
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.shadowJar {
    dependencies {
        exclude(dependency("net.kyori:adventure-api"))
    }
}

/*
 * 全量编译期子类索引（供 ClassScanner 运行时读取，加快启动）
 * 扫描主源码集全部 .class 的继承关系，输出 META-INF/voidairrace/subclass-index.txt；
 * 任务经 sourceSets.resources.srcDir 挂进资源流水线，随 jar/shadow/reobf 打包，
 * 并作为 processResources 的上游被自动调度（无需手写 dependsOn）。
 * 通用全量：新增扫描需求（ClassScanner.scanSubclasses 调用）无需改动此处配置。
 * 任务实现见 buildSrc SubclassIndexTask.kt。
 */
val generateSubclassIndex = tasks.register<SubclassIndexTask>("generateSubclassIndex") {
    group = "generation"
    description = "扫描所有 class 文件的继承关系，生成编译期子类索引资源"
    classesDirs.from(sourceSets.main.get().output.classesDirs)
    outputDir.set(layout.buildDirectory.dir("generated/subclass-index"))
}
sourceSets.main.get().resources.srcDir(generateSubclassIndex)

/*
 * 从资源包（mc原版概念）语言文件生成翻译键常量类 TranslateKeys
 * 直接覆盖 src/main/java/.../constants/TranslateKeys.java，运行本任务前请先提交或备份手动版本
 * 语言文件固定取插件自带资源包中的 assets/minecraft/lang/zh_cn.json
 * 结构为 { "翻译键": "可读文本" }
 *
 * 生成格式：TranslateKeys.<模块名>.<键名> = "语言文件中的键名原文"
 * 键名 = 匹配到的前缀之后的所有字符转大写（. 以 _ 替代以符合 Java 标识符）；语言文件中的键须为全小写
 * 前缀表（前缀 → 模块名）新增键段时需在此登记，否则对应键报错
 *
 * 空键名与键名为 "_" 的条目视为占位/分隔用途，直接跳过不生成常量
 */
tasks.register("generateTranslateKeys") {
    group = "generation"
    description = "读取插件自带资源包中的语言文件，生成 constants/TranslateKeys.java"

    doLast {
        val ns = "void_air_race" // 与 constants.Plugin.ns 保持一致

        val prefixToModule = mapOf(
            "$ns.match." to "Match",
            "$ns.match_comp." to "MatchComp",
            "$ns.match_rule." to "MatchRule",
            "$ns.rule." to "Rule",
            "$ns.map." to "Map",
            "$ns.arena." to "Arena",
            "$ns.command." to "Command",
            "$ns.team." to "Team",
            "$ns.addons." to "Addons",
            "$ns.config." to "Config",
            "$ns.audio_visual_services." to "AudioVisualServices",
            "$ns.base_components." to "BaseComponents",
            "$ns.component_registry." to "ComponentRegistry",
        )

        // 语言文件固定取插件自带资源包
        val langFile = layout.projectDirectory
            .file("src/main/resources/META-INF/resourcepack/assets/minecraft/lang/zh_cn.json").asFile
        if (!langFile.isFile)
            throw GradleException("语言文件不存在：${langFile.path}")

        val parsed = groovy.json.JsonSlurper().parseText(langFile.readText(Charsets.UTF_8))
        if (parsed !is Map<*, *>)
            throw GradleException("语言文件顶层必须是 { \"翻译键\": \"可读文本\" } 对象")

        // 逐键匹配前缀（取最长匹配，避免 "match." 抢占 "match_comp."）
        val constants = parsed.entries.mapNotNull { entry ->
            val key = entry.key as? String
                ?: throw GradleException("键必须是字符串：${entry.key}")
            // 空键与 "_" 键视为占位条目，不生成常量
            if (key.isEmpty() || key == "_") return@mapNotNull null
            val hit = prefixToModule.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { key.startsWith(it.key) }
                ?: throw GradleException("键无匹配前缀，请先在前缀表中登记：$key")
            if (key != key.lowercase())
                throw GradleException("语言文件中的键应为全小写：$key")
            val remainder = key.substring(hit.key.length)
            // 键名为空或为 "_" 同样跳过
            if (remainder.isEmpty() || remainder == "_") return@mapNotNull null
            val fieldName = remainder.uppercase().replace('.', '_')
            if (!Regex("^[A-Z][A-Z0-9_]*$").matches(fieldName))
                throw GradleException("由键名派生的字段名不是合法 Java 标识符：$key → $fieldName")
            Triple(hit.value, fieldName, key)
        }

        // 按模块分组，组内按字段名排序，模块间按名称排序，保证输出确定
        val byModule = constants.groupBy({ it.first }, { it.second to it.third })
        byModule.forEach { (module, fields) ->
            val dup = fields.map { it.first }.groupingBy { it }.eachCount().filterValues { it > 1 }
            if (dup.isNotEmpty())
                throw GradleException("模块 $module 存在派生字段名冲突：${dup.keys}")
        }

        val sb = StringBuilder()
        sb.appendLine("// 此文件由 Gradle 任务 generateTranslateKeys 生成，请勿手动编辑。")
        sb.appendLine("// 真相源：插件自带资源包中的语言文件。")
        sb.appendLine("package io.github.hhn756.voidairrace.constants;")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * 插件中的所有文本组件翻译键（生成产物）")
        sb.appendLine(" * */")
        sb.appendLine("public class TranslateKeys {")
        sb.appendLine("    private TranslateKeys() {}")
        for (module in byModule.keys.sorted()) {
            sb.appendLine()
            sb.appendLine("    public static class $module {")
            sb.appendLine("        private $module() {}")
            for ((fieldName, key) in byModule.getValue(module).sortedBy { it.first }) {
                sb.appendLine("        public static final String $fieldName = \"$key\";")
            }
            sb.appendLine("    }")
        }
        sb.appendLine("}")

        val outFile = file("src/main/java/io/github/hhn756/voidairrace/constants/TranslateKeys.java")
        outFile.parentFile.mkdirs()
        outFile.writeText(sb.toString(), Charsets.UTF_8)
        logger.lifecycle("已生成 ${byModule.values.sumOf { it.size }} 个翻译键常量 → $outFile")
    }
}

/*
 * 基于语法树扫描主源码集全部 .java 文件，报告跨行数 >= 阈值的语句块（BlockTree）起始位置，辅助拆分重构
 * 只统计「逻辑块」：方法/构造器体、初始化块、lambda、if/for/while/do/try/catch/synchronized/case 等；
 * 类体、数组初始化器、switch 体等天然允许很长的大括号在语法树中不是 BlockTree 节点，自动排除
 * 实现：JDK 自带编译器树 API（com.sun.source，无需额外依赖），TreePathScanner 遍历，
 *       Trees.getSourcePositions + LineMap 换算行列；报告顺序为同一文件内外层块先于内层块
 * 解析出错的文件按诊断逐条告警，能解析出的部分照常统计
 * 用法：gradlew findLongBracketPairs [-PminSpan=60]（缺省 60；阈值必须 >= 1）
 * 行数按「闭括号行 - 开括号行 + 1」计；列号按字符计（制表符算 1）
 */
tasks.register("findLongBracketPairs") {
    group = "inspection"
    description = "基于语法树报告主源码集中跨行数达到阈值的语句块（定位过长代码块）"

    doLast {
        val minSpan = findProperty("minSpan")?.toString()?.toIntOrNull() ?: 60
        if (minSpan < 1) throw GradleException("minSpan 必须 >= 1：$minSpan")

        val srcDir = file("src/main/java")
        if (!srcDir.isDirectory) throw GradleException("源码目录不存在：$srcDir")
        val files = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toList()
        if (files.isEmpty()) {
            logger.lifecycle("源码目录下没有 .java 文件：$srcDir")
            return@doLast
        }

        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: throw GradleException("当前 Gradle 运行在 JRE 上，拿不到系统编译器，无法解析语法树")

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, Charsets.UTF_8)
        val task = compiler.getTask(
            null,
            fileManager,
            diagnostics,
            null,
            null,
            fileManager.getJavaFileObjects(*files.toTypedArray())
        ) as JavacTask
        val cus = task.parse().toList()

        // 解析期诊断：语法错误的文件只告警，能解析出的部分照常统计
        diagnostics.diagnostics
            .filter { it.kind == Diagnostic.Kind.ERROR }
            .forEach { d ->
                val where = d.source?.toUri()?.path ?: "(未知来源)"
                logger.warn("语法错误：$where ${d.lineNumber}:${d.columnNumber} ${d.getMessage(Locale.ROOT)}")
            }

        val sourcePositions = Trees.instance(task).sourcePositions
        val srcDirPath = srcDir.toPath()
        var fileCount = 0
        var hitCount = 0

        for (cu in cus) {
            fileCount++
            val relPath = srcDirPath.relativize(Path.of(cu.sourceFile.toUri())).toString()
            val lineMap = cu.lineMap

            val scanner = object : TreePathScanner<Void?, Void?>() {
                // 块的种类由直接父节点决定
                fun blockKind(): String = when (val parent = currentPath.parentPath?.leaf) {
                    is MethodTree -> if (parent.name.toString() == "<init>") "构造器" else "方法 ${parent.name}"
                    is ClassTree -> "初始化块" // javac 语法树中初始化块 = 直接挂在类体下的 BlockTree
                    is LambdaExpressionTree -> "lambda"
                    is IfTree -> "if"
                    is ForLoopTree -> "for"
                    is EnhancedForLoopTree -> "for-each"
                    is WhileLoopTree -> "while"
                    is DoWhileLoopTree -> "do-while"
                    is TryTree -> "try"
                    is CatchTree -> "catch"
                    is SynchronizedTree -> "synchronized"
                    is CaseTree -> "case"
                    is BlockTree -> "嵌套语句块"
                    else -> parent?.kind?.toString() ?: "未知"
                }

                override fun visitBlock(node: BlockTree, p: Void?): Void? {
                    val start = sourcePositions.getStartPosition(cu, node)
                    val end = sourcePositions.getEndPosition(cu, node)
                    if (start >= 0 && end >= 0) {
                        val startLine = lineMap.getLineNumber(start).toInt()
                        val endLine = lineMap.getLineNumber(end).toInt()
                        val span = endLine - startLine + 1
                        if (span >= minSpan) {
                            hitCount++
                            val col = lineMap.getColumnNumber(start).toInt()
                            logger.lifecycle("$relPath:$startLine:$col  {…}  ${blockKind()}  跨 $span 行")
                        }
                    }
                    return super.visitBlock(node, p)
                }
            }
            scanner.scan(cu, null)
        }

        logger.lifecycle("解析 $fileCount 个文件：跨行数 >= $minSpan 的语句块共 $hitCount 处（类体/数组初始化器等非语句块括号不参与统计）")
    }
}

/*
 * 统计主源码集（src/main）全部 .java 文件中的非空行数
 * 非空行 = 去除行首行尾空白后仍有内容的行（纯空白行不计）
 * 用法：gradlew countNonEmptyLines [-Pdetail]（带 detail 时逐文件列出各自非空行数）
 */
tasks.register("countNonEmptyLines") {
    group = "inspection"
    description = "统计 src/main 下所有 .java 文件的非空行数"

    doLast {
        val srcDir = file("src/main")
        if (!srcDir.isDirectory) throw GradleException("目录不存在：$srcDir")
        val files = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toList()
        if (files.isEmpty()) {
            logger.lifecycle("目录下没有 .java 文件：$srcDir")
            return@doLast
        }

        var total = 0L
        // 文件按相对路径排序，保证输出确定
        val perFile = files.associate { f ->
            val count = f.readText(Charsets.UTF_8).lineSequence().count { it.isNotBlank() }
            total += count
            srcDir.toPath().relativize(f.toPath()).toString() to count
        }.toSortedMap()

        if (project.hasProperty("detail"))
            perFile.forEach { (path, count) -> logger.lifecycle("$path  $count") }
        logger.lifecycle("共 ${files.size} 个 .java 文件，非空行总计 $total 行")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "utf-8"
}

val deployDir = System.getenv("DEPLOY_DIR")?.replace("\\", "/") ?: "build/deploy"

// 输出构建结果jar至服务端插件目录
tasks.reobfJar {
    val fileName = "${project.name}.jar"

    // 调试信息
    doFirst {
        println("========================================")
        println("DEPLOY_DIR 环境变量: ${System.getenv("DEPLOY_DIR")}")
        println("处理后路径: $deployDir")
        println("文件名: $fileName")
        println("完整路径: $deployDir/$fileName")
        println("========================================")

        // 确保目录存在
        val targetDir = file(deployDir)
        println("目标目录是否存在: ${targetDir.exists()}")
        if (!targetDir.exists()) {
            println("创建目录: ${targetDir.absolutePath}")
            targetDir.mkdirs()
            println("目录创建成功: ${targetDir.exists()}")
        }
    }

    // 使用 File 构造函数而不是 layout
    outputJar.set(file("$deployDir/$fileName"))

    doLast {
        println("任务执行完成")
        println("输出文件路径: ${outputJar.get().asFile.absolutePath}")
        println("文件是否存在: ${outputJar.get().asFile.exists()}")
        println("文件大小: ${outputJar.get().asFile.length()} 字节")
    }
}

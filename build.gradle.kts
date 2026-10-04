import voidairrace.build.SubclassIndexTask

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
    // 许可证：3-Clause BSD（https://asm.ow2.io/license.html）
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

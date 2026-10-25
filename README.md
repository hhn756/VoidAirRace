# 虚空飞行赛

这是一个 Minecraft 小游戏

目前还在早期开发阶段，不保证方法声明、类结构、包结构和标识符名称的稳定性，也不保证没有bug

## 许可证

本项目的**所有原创内容**（包括但不限于源代码、资源文件（纹理、音效、模型）、配置文件和文档）均采用 **Mozilla Public License 2.0 (MPL-2.0)** 授权。

“原创内容”指仓库中除下文“第三方组件”一节表格所列组件，以及“构建工具”一节所列的 Gradle Wrapper 文件之外的所有内容；这些第三方内容不适用本授权，遵循各自的许可证。

完整的 MPL-2.0 许可证文本请见项目根目录下的 [LICENSE](LICENSE) 文件。

### 第三方组件

本项目包含以下第三方组件，它们遵循各自的许可证：

| 组件     | 项目主页                                                                         | 组件位置                                         | 许可证             |
|----------|----------------------------------------------------------------------------------|--------------------------------------------------|--------------------|
| Rembulan | [github.com/wizards-of-lua/rembulan](https://github.com/wizards-of-lua/rembulan) | `libs/rembulan`                                  | Apache License 2.0 |
| ASM      | [asm.ow2.io](https://asm.ow2.io/)                                                | Gradle 依赖（`org.ow2.asm:asm` 6.2 等 4 个构件） | BSD-3-Clause       |

Rembulan 与 ASM 的许可证全文分别见 [libs/rembulan/LICENSE.txt](libs/rembulan/LICENSE.txt) 与 [libs/asm/LICENSE.txt](libs/asm/LICENSE.txt)。

打包发布时，上述许可证文本会随构建进入发布 jar 的 `META-INF/`（`LICENSE-Voidairrace.txt`、`LICENSE-Rembulan.txt`、`LICENSE-ASM.txt`）。

### 构建工具

项目自带的 Gradle Wrapper（`gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar`）来自 [Gradle](https://gradle.org/) 项目，采用 Apache License 2.0 授权，两个脚本的文件头部保留了原始声明。`gradle/wrapper/gradle-wrapper.properties` 只是 wrapper 的本地配置，不含代码。

Gradle Wrapper 仅用于构建，不进入发布 jar，因此其许可证文本不在 `META-INF/` 中。

`build.gradle.kts`、`settings.gradle.kts` 及 `buildSrc/` 中的构建脚本为本项目原创内容，适用上文的 MPL-2.0。
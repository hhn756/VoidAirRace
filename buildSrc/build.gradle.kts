plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // 读取 class 文件头的继承关系，只需解析 constant pool 与类声明，不加载类
    implementation("org.ow2.asm:asm:9.8")
}

package io.github.hhn756.voidairrace.constants;

/**
 * 插件中资源文件的路径，不包含后缀
 * */
public enum ResourcePath {
    /** jar 内插件自带配置文件的目录 */
    META_INF("META-INF/"),
    DATAPACK("/META-INF/datapack/");

    private final String path;

    ResourcePath(String path) {
        this.path = path;
    }

    /**
     * 获取字符串形式的资源路径
     * */
    @Override
    public String toString() {
        return path;
    }
}

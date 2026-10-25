package io.github.hhn756.voidairrace.addons.script;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.service.language.ServerLanguageService;
import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * 脚本标准输出/错误流的重定向管道：按行解码为 UTF-8 文本写入插件日志<br>
 * print 的内容由 Lua 侧逐段写入，此处只负责攒行
 * */
public final class LogOutputStream extends OutputStream {
    private final @NonNull Logger logger;
    private final @NonNull ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    public LogOutputStream(@NonNull Logger logger) {
        this.logger = logger;
    }

    @Override
    public void write(int b) {
        if (b == '\n') {
            flushLine();
        } else {
            buffer.write(b);
        }
    }

    @Override
    public void flush() {
        flushLine();
    }

    private void flushLine() {
        if (buffer.size() == 0) return;
        logger.info(ServerLanguageService.translate(
                TranslateKeys.Log.SCRIPT_OUTPUT, buffer.toString(StandardCharsets.UTF_8)));
        buffer.reset();
    }
}

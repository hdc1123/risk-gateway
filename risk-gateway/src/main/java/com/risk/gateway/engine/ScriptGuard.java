package com.risk.gateway.engine;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** 基础防误用检查，不是可安全执行不可信代码的隔离沙箱。仅允许受信任管理员写规则。 */
@Component
public class ScriptGuard {
    private static final Pattern DECLARATIONS = Pattern.compile("(?m)\\b(import|package|class|interface|trait|enum)\\b|@");
    private static final List<String> FORBIDDEN = List.of(
        "System", "Runtime", "ProcessBuilder", "ClassLoader", "forName", "getClass",
        "class", "metaClass", "MetaClass", "invokeMethod", "evaluate", "GroovyShell",
        "GroovyClassLoader", "java.", "javax.", "jakarta.", "groovy.", "sun.", "jdk.",
        "Thread", "sleep", "File", "Socket", "URL", "getResource", "getBinding",
        "binding", "getProperty", "setProperty", "properties", "execute", "finalize");

    public void validate(String source, int maxBytes) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("脚本内容不能为空");
        if (source.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("脚本超过最大字节数");
        if (DECLARATIONS.matcher(source).find()) throw new SecurityException("脚本禁止导入、类型声明和注解");
        for (String token : FORBIDDEN) {
            if (source.contains(token)) throw new SecurityException("脚本包含禁止内容: " + token);
        }
    }
}

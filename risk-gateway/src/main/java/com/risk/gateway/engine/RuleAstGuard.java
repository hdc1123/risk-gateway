package com.risk.gateway.engine;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import java.util.Set;

/** 语法级纵深防御，不是安全沙箱。禁止动态成员名、类型引用和对象构造。 */
final class RuleAstGuard extends CompilationCustomizer {
    private static final Set<String> MEMBERS = Set.of(
        "class", "metaClass", "getClass", "getMetaClass", "setMetaClass", "invokeMethod",
        "getProperty", "setProperty", "getAttribute", "setAttribute", "getBinding", "setBinding",
        "binding", "properties", "evaluate", "execute", "getResource", "forName");
    RuleAstGuard() { super(CompilePhase.CONVERSION); }
    @Override public void call(SourceUnit source, GeneratorContext context, ClassNode node) {
        node.visitContents(new ClassCodeVisitorSupport() {
            @Override protected SourceUnit getSourceUnit() { return source; }
            @Override public void visitMethod(MethodNode method) {
                // Groovy 自动生成的 main 使用运行时类型引用，不是用户脚本。
                if (method.getName().equals("main") && method.getLineNumber() < 0) return;
                super.visitMethod(method);
            }
            @Override public void visitConstructorCallExpression(ConstructorCallExpression expression) {
                if (!expression.isSpecialCall()) reject("禁止构造对象");
                super.visitConstructorCallExpression(expression);
            }
            @Override public void visitClassExpression(ClassExpression expression) {
                reject("禁止类型引用");
            }
            @Override public void visitStaticMethodCallExpression(StaticMethodCallExpression expression) {
                reject("禁止静态方法调用");
            }
            @Override public void visitMethodPointerExpression(MethodPointerExpression expression) {
                reject("禁止方法指针");
            }
            @Override public void visitMethodCallExpression(MethodCallExpression expression) {
                String method = expression.getMethodAsString();
                if (method == null || MEMBERS.contains(method)) reject("禁止动态或敏感方法调用");
                super.visitMethodCallExpression(expression);
            }
            @Override public void visitPropertyExpression(PropertyExpression expression) {
                String property = expression.getPropertyAsString();
                if (property == null || MEMBERS.contains(property)) reject("禁止动态或敏感属性访问");
                super.visitPropertyExpression(expression);
            }
            private void reject(String message) { throw new SecurityException(message); }
        });
    }
}

package com.risk.gateway.routing;

import com.risk.gateway.Models.*;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;

@Component
public class RoutingDecider {
    private final java.util.function.Supplier<Book> defaultBook;
    public RoutingDecider() { this.defaultBook = () -> Book.B_BOOK; }
    @org.springframework.beans.factory.annotation.Autowired
    public RoutingDecider(com.risk.gateway.config.SystemConfigService configs) {
        this.defaultBook = () -> configs.current().defaultBook();
    }
    /** 单条规则失败跳过，仍可采用其他成功规则；降级标记由网关保留。 */
    public Decision decide(Evaluation evaluation) {
        return decide(evaluation.hits());
    }

    public Decision decide(List<RuleResult> results) {
        return results.stream()
            .filter(r -> r != null && "ROUTE".equals(r.action()) && "book_type".equals(r.target()) && r.value() != null)
            .sorted(Comparator.comparingInt(RuleResult::priority).reversed().thenComparing(RuleResult::ruleId))
            .findFirst()
            .map(r -> new Decision(r.value(), "命中规则: " + r.ruleId(), List.of(r.ruleId())))
            .orElseGet(() -> {
                Book book = defaultBook.get();
                return new Decision(book, "未命中有效路由规则，默认 " + book.name(), List.of());
            });
    }
}

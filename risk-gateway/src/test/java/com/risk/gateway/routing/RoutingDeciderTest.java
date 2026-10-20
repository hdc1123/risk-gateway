package com.risk.gateway.routing;

import com.risk.gateway.Models.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RoutingDeciderTest {
    private final RoutingDecider decider = new RoutingDecider();
    private RuleResult hit(String id, int priority, Book book) {
        return new RuleResult(id, id, priority, "ROUTE", "book_type", book);
    }
    @Test
    void highestPriorityWinsWithoutMutatingInput() {
        var input = List.of(hit("LOW", 1, Book.A_BOOK), hit("HIGH", 100, Book.B_BOOK));
        assertEquals(Book.B_BOOK, decider.decide(input).bookType());
        assertEquals(List.of("HIGH"), decider.decide(input).ruleIds());
        assertEquals("LOW", input.get(0).ruleId());
    }
    @Test
    void stableTieBreakAndDefault() {
        assertEquals(List.of("A"), decider.decide(List.of(hit("Z", 10, Book.B_BOOK), hit("A", 10, Book.A_BOOK))).ruleIds());
        assertEquals(Book.B_BOOK, decider.decide(List.of()).bookType());
        assertTrue(decider.decide(List.of()).ruleIds().isEmpty());
    }
}

package com.risk.gateway.logging;

import com.risk.gateway.Models.DecisionContext;

public interface DecisionLogSink {
    void write(String logKey, DecisionContext context);
}

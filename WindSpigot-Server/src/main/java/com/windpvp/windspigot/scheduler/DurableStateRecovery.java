
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Crash-recovery validator (W4-CRASH). Verifies commerce invariants.
 */
public final class DurableStateRecovery {

    public static final class RecoveryReport {
        private final int totalOperations;
        private final int uniqueTxIds;
        private final List<String> anomalies;

        public RecoveryReport(int totalOperations, int uniqueTxIds, List<String> anomalies) {
            this.totalOperations = totalOperations;
            this.uniqueTxIds = uniqueTxIds;
            this.anomalies = Collections.unmodifiableList(new ArrayList<>(anomalies));
        }

        public int totalOperations() { return totalOperations; }
        public int uniqueTxIds() { return uniqueTxIds; }
        public List<String> anomalies() { return anomalies; }
        public boolean isClean() { return anomalies.isEmpty(); }
    }

    private DurableStateRecovery() {}

    public static RecoveryReport verify(DurableOperationLog log, Set<String> expectedTypes) {
        List<String> anomalies = new ArrayList<>();
        int total = log.size();
        int unique = log.appliedTxIds().size();
        if (total != unique) anomalies.add("duplicate txIds detected");
        for (String txId : log.appliedTxIds()) {
            String type = log.operationType(txId);
            if (type == null || type.isEmpty()) anomalies.add("empty type for: " + txId);
            else if (expectedTypes != null && !expectedTypes.contains(type)) anomalies.add("unknown type '" + type + "' for: " + txId);
        }
        return new RecoveryReport(total, unique, anomalies);
    }
}


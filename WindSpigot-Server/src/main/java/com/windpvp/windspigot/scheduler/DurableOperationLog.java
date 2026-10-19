
package com.windpvp.windspigot.scheduler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only durable operation log (W4-CRASH/STORAGE). Idempotent by txId.
 */
public final class DurableOperationLog {

    private final Map<String, String> applied = new ConcurrentHashMap<>();
    private final Path logFile;
    private BufferedWriter writer;

    public DurableOperationLog(Path logFile) throws IOException {
        this.logFile = logFile;
        if (Files.exists(logFile)) {
            replay();
        }
        this.writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public boolean record(String txId, String operationType) throws IOException {
        if (applied.containsKey(txId)) return false;
        applied.put(txId, operationType);
        writer.write(txId + "|" + operationType);
        writer.newLine();
        writer.flush();
        return true;
    }

    public boolean isApplied(String txId) { return applied.containsKey(txId); }
    public String operationType(String txId) { return applied.get(txId); }
    public int size() { return applied.size(); }
    public Set<String> appliedTxIds() { return Collections.unmodifiableSet(applied.keySet()); }

    private void replay() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int sep = line.indexOf('|');
                if (sep > 0) applied.put(line.substring(0, sep), line.substring(sep + 1));
            }
        }
    }

    public void close() throws IOException {
        if (writer != null) { writer.flush(); writer.close(); writer = null; }
    }

    public boolean isClosed() { return writer == null; }
}


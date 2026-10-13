package com.windpvp.windspigot.database;

import java.util.HashMap;
import java.util.Map;

import com.avaje.ebean.config.DataSourceConfig;

/** Applies conservative SQLite settings for concurrent plugin reads and writes. */
public final class SQLiteDatabaseConfig {

    private SQLiteDatabaseConfig() {
    }

    public static void apply(DataSourceConfig dataSource) {
        dataSource.setMinConnections(1);
        dataSource.setMaxConnections(4);

        Map<String, String> properties = dataSource.getCustomProperties();
        if (properties == null) {
            properties = new HashMap<String, String>();
            dataSource.setCustomProperties(properties);
        }

        properties.put("journal_mode", "WAL");
        properties.put("synchronous", "NORMAL");
        properties.put("foreign_keys", "true");
    }
}

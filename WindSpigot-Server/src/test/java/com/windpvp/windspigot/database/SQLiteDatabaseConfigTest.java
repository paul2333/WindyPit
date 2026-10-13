package com.windpvp.windspigot.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Map;

import org.junit.Test;

import com.avaje.ebean.config.DataSourceConfig;

public class SQLiteDatabaseConfigTest {

    @Test
    public void appliesConcurrentReadPragmasToSqliteDataSource() {
        DataSourceConfig dataSource = new DataSourceConfig();

        SQLiteDatabaseConfig.apply(dataSource);

        Map<String, String> properties = dataSource.getCustomProperties();
        assertNotNull(properties);
        assertEquals("WAL", properties.get("journal_mode"));
        assertEquals("NORMAL", properties.get("synchronous"));
        assertEquals("true", properties.get("foreign_keys"));
        assertEquals(1, dataSource.getMinConnections());
        assertEquals(4, dataSource.getMaxConnections());
    }
}

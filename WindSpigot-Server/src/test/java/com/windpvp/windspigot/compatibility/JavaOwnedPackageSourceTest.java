package com.windpvp.windspigot.compatibility;

import static org.junit.Assert.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class JavaOwnedPackageSourceTest {

    @Test
    public void productionSourcesDoNotDeclareClassesInJdkOwnedSunReflectPackage() {
        Path legacyShim = Paths.get("src", "main", "java", "sun", "reflect", "Reflection.java");

        assertFalse("JDK 9+ owns sun.reflect and rejects application sources in that package",
                Files.exists(legacyShim));
    }
}

package com.qa.utils;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;


public class ConfigManager {


    private static final String CONFIG_FILE = "config.properties";
    private static final AtomicReference<Properties> CONFIG_PROPS = new AtomicReference<>();


    /**
     * Loads and returns properties from config.properties.
     * Lazily initializes once in a thread-safe manner.
     */
    public Properties getConfigProps() {
        Properties current = CONFIG_PROPS.get();
        if (current != null) {
            return current;
        }


        Properties loaded = loadProperties();
        if (CONFIG_PROPS.compareAndSet(null, loaded)) {
            return loaded; // first thread wins
        }
        return CONFIG_PROPS.get(); // another thread already set it
    }


    /**
     * Loads the config.properties file from the classpath.
     */
    private Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream is = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {


            if (is == null) {
                throw new FileNotFoundException(
                        "Property file '" + CONFIG_FILE + "' not found in the classpath");
            }


            props.load(is);
            TestUtils.log().info("Loaded configuration from file: {}", CONFIG_FILE);


        } catch (IOException e) {
            TestUtils.log().fatal(
                    "Error loading property file '{}'. Exception: {}", CONFIG_FILE, e.getMessage());
            // props stays empty but valid
        }


        return props;
    }
}

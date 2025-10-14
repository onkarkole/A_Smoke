package com.qa.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class TestUtils {
    public static final long WAITFOR = 60;

    private TestUtils() {
        throw new UnsupportedOperationException("Utility class — instantiation not allowed");
    }

    public static Logger log() {
        return LogManager.getLogger(Thread.currentThread().getStackTrace()[2].getClassName());
    }
}
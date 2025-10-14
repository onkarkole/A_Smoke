package com.qa.common;


import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v127.network.Network;

import java.util.Optional;


public class DevToolsManager {


    // Prevent instantiation
    private DevToolsManager() {
        throw new UnsupportedOperationException("Common class");
    }


    private static final ThreadLocal<DevTools> tlDevTools = new ThreadLocal<>();


    public static void initializeDevTools(ChromeDriver driver) {
        DevTools devTools = driver.getDevTools();
        devTools.createSession();
        devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty()));
        tlDevTools.set(devTools);
    }


    public static DevTools getDevTools() {
        return tlDevTools.get();
    }


    public static void clearDevTools() {
        tlDevTools.remove();
    }

}

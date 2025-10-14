package com.qa.common;

public class SessionManager {
    private static String currentRole = "";
    private static boolean reLoginNeeded = false;
    private static boolean isFirstScenario = true;
    private static boolean isAlreadyLoggedOut = false;


    private SessionManager() {
        throw new UnsupportedOperationException("common class");
    }


    public static String getCurrentRole() {
        return currentRole;
    }


    public static void setCurrentRole(String role) {
        currentRole = role;
    }


    public static boolean isRoleSwitch(String newRole) {
        return !currentRole.equalsIgnoreCase(newRole);
    }


    public static void markReLoginNeeded() {
        reLoginNeeded = true;
    }


    public static boolean shouldReLogin() {
        return reLoginNeeded;
    }


    public static void resetReLoginFlag() {
        reLoginNeeded = false;
    }


    public static boolean isFirstScenario() {
        return isFirstScenario;
    }


    public static void markFirstScenarioCompleted() {
        isFirstScenario = false;
    }


    public static boolean isAlreadyLoggedOut() {
        return isAlreadyLoggedOut;
    }


    public static void markLoggedOut() {
        isAlreadyLoggedOut = true;
    }


    public static void resetLoggedOutFlag() {
        isAlreadyLoggedOut = false;
    }
}

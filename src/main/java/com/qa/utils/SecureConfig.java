package com.qa.utils;

/**
 * Central secure configuration provider.
 * Loads SecureEnv only once (lazy & thread-safe) and makes it
 * accessible across the entire framework (main + test).
 *
 * Usage example:
 *   String baseUrl = SecureConfig.value(SecKeys.BASE_URL);
 *   String user    = SecureConfig.get().getByToken(SecKeys.USERNAME);
 */
public final class SecureConfig {

    // Holds the singleton instance of SecureEnv
    private static volatile SecureEnv SEC;

    // Private constructor to prevent external instantiation
    private SecureConfig() {}

    /**
     * Returns the single SecureEnv instance, initializing it lazily.
     * Thread-safe double-checked locking ensures it's loaded only once.
     */
    public static SecureEnv get() {
        if (SEC == null) {
            synchronized (SecureConfig.class) {
                if (SEC == null) {
                    SEC = SecureEnv.load();
                }
            }
        }
        return SEC;
    }

    /**
     * Optional explicit bootstrap — same as calling get().
     * Can be invoked early (e.g., in ProjectHooks @Before).
     */
    public static void init() {
        get();
    }

    /**
     * Convenience shortcut for fetching a decrypted value directly.
     *
     * @param token key name from SecKeys
     * @return decrypted value as plain text
     */
    public static String value(String token) {
        return get().getByToken(token);
    }
}

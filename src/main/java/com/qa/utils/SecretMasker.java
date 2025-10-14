package com.qa.utils;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SecretMasker {
    private static final Set<String> SECRETS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final String MASK = "********";
    private SecretMasker() {}

    public static void registerSecret(String s) {
        if (s != null && s.length() >= 2) SECRETS.add(s);
    }

    public static String mask(String msg) {
        if (msg == null || msg.isEmpty()) return msg;
        String out = msg;
        for (String s : SECRETS) out = out.replace(s, MASK);
        out = out.replaceAll("K\\|[a-f0-9]{64}", "K|********");
        out = out.replaceAll("V\\|[A-Za-z0-9_-]+", "V|********");
        return out;
    }

    public static Object[] maskArgs(Object... args) {
        if (args == null) return null;
        Object[] masked = new Object[args.length];
        for (int i = 0; i < args.length; i++) masked[i] = mask(String.valueOf(args[i]));
        return masked;
    }
}
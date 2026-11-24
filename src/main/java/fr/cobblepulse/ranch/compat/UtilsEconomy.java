package fr.cobblepulse.ranch.compat;

import fr.cobblepulse.ranch.CobbleRanch;

import java.lang.reflect.Method;
import java.util.UUID;

/** Economie de CobblePulse Utils (CobbleDollars / Impactor), par reflexion : aucune dependance de compilation. */
public final class UtilsEconomy {
    private static Method has, withdraw, format;
    private static boolean tried;

    private UtilsEconomy() {}

    private static boolean init() {
        if (tried) return has != null;
        tried = true;
        try {
            Class<?> c = Class.forName("fr.cobblepulse.utils.integration.Economy");
            has = c.getMethod("has", UUID.class, long.class);
            withdraw = c.getMethod("withdraw", UUID.class, long.class);
            format = c.getMethod("format", long.class);
        } catch (Throwable t) {
            has = null;
        }
        return has != null;
    }

    public static boolean available() {
        return init();
    }

    public static boolean has(UUID u, long amount) {
        try {
            return init() && (boolean) has.invoke(null, u, amount);
        } catch (Throwable t) {
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Economie : {}", t.toString());
            return false;
        }
    }

    public static boolean withdraw(UUID u, long amount) {
        try {
            return init() && (boolean) withdraw.invoke(null, u, amount);
        } catch (Throwable t) {
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Economie : {}", t.toString());
            return false;
        }
    }

    public static String format(long amount) {
        try {
            if (init()) return (String) format.invoke(null, amount);
        } catch (Throwable ignored) {
        }
        return amount + " ₽";
    }
}

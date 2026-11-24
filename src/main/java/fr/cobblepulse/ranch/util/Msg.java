package fr.cobblepulse.ranch.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/** Messages du mod (prefixe [CobbleRanch]) et petits formats. */
public final class Msg {
    private Msg() {}

    public static MutableComponent prefix() {
        return Component.literal("[CobbleRanch] ").withStyle(ChatFormatting.GOLD);
    }

    public static void ok(ServerPlayer p, String text) {
        p.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.GREEN)));
    }

    public static void info(ServerPlayer p, String text) {
        p.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    public static void err(ServerPlayer p, String text) {
        p.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
    }

    public static void send(ServerPlayer p, Component c) {
        p.sendSystemMessage(prefix().append(c));
    }

    /** 3725 s -> "1h 02m 05s". */
    public static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format(Locale.ROOT, "%dm %02ds", m, s);
        return s + "s";
    }

    /** Texte legacy (&a...) vers composant. */
    public static MutableComponent legacy(String s) {
        MutableComponent out = Component.empty();
        ChatFormatting color = ChatFormatting.WHITE;
        boolean bold = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                ChatFormatting f = ChatFormatting.getByCode(s.charAt(i + 1));
                if (f != null) {
                    if (cur.length() > 0) {
                        MutableComponent part = Component.literal(cur.toString()).withStyle(color);
                        if (bold) part = part.withStyle(ChatFormatting.BOLD);
                        out.append(part);
                        cur.setLength(0);
                    }
                    if (f == ChatFormatting.BOLD) bold = true;
                    else if (f == ChatFormatting.RESET) {
                        color = ChatFormatting.WHITE;
                        bold = false;
                    } else if (f.isColor()) {
                        color = f;
                        bold = false;
                    }
                    i++;
                    continue;
                }
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            MutableComponent part = Component.literal(cur.toString()).withStyle(color);
            if (bold) part = part.withStyle(ChatFormatting.BOLD);
            out.append(part);
        }
        return out;
    }
}

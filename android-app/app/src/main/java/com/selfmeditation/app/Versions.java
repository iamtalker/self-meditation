package com.selfmeditation.app;

import java.util.ArrayList;
import java.util.List;

// Version-string helpers (pure Java so they can be tested without Android).
final class Versions {

    private Versions() {}

    /** "v1.4.6" or "1.4.6" -> [1, 4, 6] */
    static List<Integer> parts(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        List<Integer> out = new ArrayList<>();
        for (String piece : s.split("\\.")) {
            int end = 0;
            while (end < piece.length() && Character.isDigit(piece.charAt(end))) end++;
            out.add(end == 0 ? 0 : Integer.parseInt(piece.substring(0, Math.min(end, 9))));
        }
        return out;
    }

    /** True when {@code latest} is a higher version than {@code current}, compared numerically. */
    static boolean newer(String latest, String current) {
        List<Integer> a = parts(latest);
        List<Integer> b = parts(current);
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) return x > y;
        }
        return false;
    }
}

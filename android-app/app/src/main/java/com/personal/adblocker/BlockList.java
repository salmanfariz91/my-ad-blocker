package com.personal.adblocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The set of ad domains. A name is blocked if it, or any parent domain, is in the set,
 * unless the name or a closer parent is in the user's allow list.
 *
 * Sources: the built-in list (assets), the downloaded list (if any), and the domains the
 * user blocked or allowed by tapping them in the app.
 */
final class BlockList {
    static final String DOWNLOADED_FILE = "downloaded.txt";
    private static final String PREFS = "lists";
    private static final String KEY_BLOCK = "block";
    private static final String KEY_ALLOW = "allow";

    private final Set<String> domains = new HashSet<>();
    private final Set<String> allow = new HashSet<>();

    static BlockList load(Context context) {
        BlockList list = new BlockList();
        try {
            list.read(context.getAssets().open("blocklist.txt"));
        } catch (IOException e) {
            // Built-in list unreadable: carry on with whatever else loads.
        }
        File downloaded = new File(context.getFilesDir(), DOWNLOADED_FILE);
        if (downloaded.exists()) {
            try {
                list.read(new FileInputStream(downloaded));
            } catch (IOException e) {
                // Ignore a broken download; the built-in list still applies.
            }
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        list.domains.addAll(prefs.getStringSet(KEY_BLOCK, Collections.emptySet()));
        list.allow.addAll(prefs.getStringSet(KEY_ALLOW, Collections.emptySet()));
        return list;
    }

    /** Saves the user's choice for one domain: block it, or allow it. */
    static void setCustom(Context context, String domain, boolean block) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> blocked = new HashSet<>(prefs.getStringSet(KEY_BLOCK, Collections.emptySet()));
        Set<String> allowed = new HashSet<>(prefs.getStringSet(KEY_ALLOW, Collections.emptySet()));
        if (block) {
            allowed.remove(domain);
            blocked.add(domain);
        } else {
            blocked.remove(domain);
            allowed.add(domain);
        }
        prefs.edit().putStringSet(KEY_BLOCK, blocked).putStringSet(KEY_ALLOW, allowed).apply();
    }

    private void read(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = parseDomain(line);
                if (domain != null) domains.add(domain);
            }
        }
    }

    /**
     * Reads one list line. Accepts "example.com", hosts lines such as "0.0.0.0 example.com",
     * and simple adblock rules such as "||example.com^". Returns null for anything else.
     */
    static String parseDomain(String line) {
        // "#" starts a comment only at the start of the line or after a space. Anywhere
        // else (such as "site.com##.ad") the line is not a domain entry and is rejected.
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '#'
                    && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                line = line.substring(0, i);
                break;
            }
        }
        line = line.trim().toLowerCase(Locale.ROOT);
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("@@")) return null;

        String[] parts = line.split("\\s+");
        String d = parts[parts.length - 1];
        if (d.startsWith("||")) d = d.substring(2);
        if (d.startsWith("*.")) d = d.substring(2);
        if (d.endsWith("^")) d = d.substring(0, d.length() - 1);

        if (d.length() < 4 || d.length() > 253 || d.indexOf('.') <= 0 || d.endsWith(".")) {
            return null;
        }
        boolean hasLetter = false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c >= 'a' && c <= 'z') {
                hasLetter = true;
            } else if (!((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_')) {
                return null;
            }
        }
        return hasLetter ? d : null; // no letters means an IP address, not a domain
    }

    /**
     * Reads one line of an adblock filter list such as EasyList. Only whole-domain ad
     * rules are usable for DNS blocking: "||example.com^" and "||example.com^$third-party".
     * Everything else (address patterns, hiding rules, exceptions) returns null.
     */
    static String parseAdblockDomain(String line) {
        String s = line.trim();
        if (!s.startsWith("||")) return null;
        s = s.substring(2);
        String thirdParty = "^$third-party";
        if (s.endsWith(thirdParty)) {
            s = s.substring(0, s.length() - thirdParty.length());
        } else if (s.endsWith("^")) {
            s = s.substring(0, s.length() - 1);
        } else {
            return null;
        }
        if (s.isEmpty() || s.indexOf(' ') >= 0) return null;
        return parseDomain(s);
    }

    int size() {
        return domains.size();
    }

    boolean isBlocked(String name) {
        String d = name;
        while (true) {
            if (allow.contains(d)) return false;
            if (domains.contains(d)) return true;
            int dot = d.indexOf('.');
            if (dot < 0) return false;
            d = d.substring(dot + 1);
        }
    }
}

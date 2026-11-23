package com.personal.adblocker;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Downloads public ad-domain lists and saves them for BlockList to load. */
final class ListUpdater {
    private ListUpdater() {}

    private static final String PREFS = "updates";
    private static final String KEY_UPDATED = "updated";

    private static final String[] SOURCES = {
            // HaGeZi Pop-Up Ads: pop-up and redirect ad domains.
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt",
            // AdAway default list: mobile and in-app ad providers.
            "https://adaway.org/hosts.txt",
    };

    // These are in adblock filter format; only their whole-domain rules are used.
    private static final String[] ADBLOCK_SOURCES = {
            // EasyList: ads.
            "https://easylist.to/easylist/easylist.txt",
            // EasyPrivacy: trackers and analytics.
            "https://easylist.to/easylist/easyprivacy.txt",
    };

    /** Returns the number of domains saved. Throws if nothing usable could be downloaded. */
    static synchronized int update(Context context) throws IOException {
        Set<String> domains = new HashSet<>();
        IOException lastError = null;
        for (String source : SOURCES) {
            try {
                download(source, false, domains);
            } catch (IOException e) {
                lastError = e; // keep going: one working source is still useful
            }
        }
        for (String source : ADBLOCK_SOURCES) {
            try {
                download(source, true, domains);
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (domains.size() < 1000) {
            throw lastError != null ? lastError : new IOException("Lists were empty");
        }

        File tmp = new File(context.getFilesDir(), BlockList.DOWNLOADED_FILE + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (String d : domains) {
                writer.write(d);
                writer.write('\n');
            }
        }
        File target = new File(context.getFilesDir(), BlockList.DOWNLOADED_FILE);
        if (!tmp.renameTo(target)) {
            throw new IOException("Could not save the list");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
        return domains.size();
    }

    /** When the lists were last downloaded, in milliseconds, or 0 if never. */
    static long lastUpdated(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_UPDATED, 0);
    }

    private static void download(String source, boolean adblockFormat, Set<String> into)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "adblock-list-updater");
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String domain = adblockFormat
                            ? BlockList.parseAdblockDomain(line)
                            : BlockList.parseDomain(line);
                    if (domain != null) into.add(domain);
                }
            }
        } finally {
            conn.disconnect();
        }
    }
}

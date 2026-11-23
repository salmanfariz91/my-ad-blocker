package com.personal.adblocker;

import java.util.Arrays;
import java.util.Locale;

/** Minimal DNS message helpers: read the question name, build a "no such domain" answer. */
final class DnsUtil {
    private DnsUtil() {}

    /** Offset just past the first question (name + type + class), or -1 if malformed. */
    static int questionEnd(byte[] msg) {
        if (msg.length < 12) return -1;
        int questions = ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        if (questions < 1) return -1;
        int pos = 12;
        while (true) {
            if (pos >= msg.length) return -1;
            int len = msg[pos] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) != 0) return -1; // compression is not valid in a question
            pos += len + 1;
        }
        int end = pos + 1 + 4;
        return end <= msg.length ? end : -1;
    }

    /** The queried name in lower case, e.g. "ads.example.com", or null if malformed. */
    static String questionName(byte[] msg) {
        if (questionEnd(msg) < 0) return null;
        StringBuilder sb = new StringBuilder();
        int pos = 12;
        while (true) {
            int len = msg[pos] & 0xFF;
            if (len == 0) break;
            if (sb.length() > 0) sb.append('.');
            for (int i = 1; i <= len; i++) {
                sb.append((char) (msg[pos + i] & 0xFF));
            }
            pos += len + 1;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** An NXDOMAIN reply to the given query, or null if the query is malformed. */
    static byte[] blockedResponse(byte[] query) {
        int end = questionEnd(query);
        if (end < 0) return null;
        byte[] r = Arrays.copyOf(query, end);
        r[2] = (byte) (0x80 | (query[2] & 0x79)); // response, keep opcode and "recursion desired"
        r[3] = (byte) 0x83;                       // recursion available, NXDOMAIN
        r[4] = 0;
        r[5] = 1;                                 // one question
        for (int i = 6; i < 12; i++) r[i] = 0;    // no answer, authority or additional records
        return r;
    }
}

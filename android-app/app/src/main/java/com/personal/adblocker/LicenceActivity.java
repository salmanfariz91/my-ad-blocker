package com.personal.adblocker;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Shows the app's version, licence notice, block list credits and the full GPL-3.0 text. */
public class LicenceActivity extends Activity {
    private static final String SOURCE_URL = "https://github.com/salmanfariz91/my-ad-blocker";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Licence and credits");

        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView notice = new TextView(this);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        notice.setTextIsSelectable(true);
        notice.setAutoLinkMask(Linkify.WEB_URLS);
        notice.setText(
                "My Ad Blocker, version " + versionName() + "\n"
                + "Copyright (C) 2026 salmanfariz91\n\n"
                + "This program is free software: you can redistribute it and/or modify it "
                + "under the terms of the GNU General Public License, version 3, as "
                + "published by the Free Software Foundation.\n\n"
                + "This program is distributed in the hope that it will be useful, but "
                + "WITHOUT ANY WARRANTY; without even the implied warranty of "
                + "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the licence "
                + "below for details.\n\n"
                + "Source code:\n" + SOURCE_URL + "\n\n"
                + "Block lists\n"
                + "The lists this app downloads are made by other people and are used "
                + "under their own licences:\n"
                + "• EasyList and EasyPrivacy: GPLv3 / CC BY-SA 3.0\n"
                + "• HaGeZi Pop-Up Ads: GPLv3\n"
                + "• AdAway default blocklist: CC BY 3.0\n\n"
                + "Full licence text");
        column.addView(notice);

        TextView licence = new TextView(this);
        licence.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        licence.setTypeface(Typeface.MONOSPACE);
        licence.setTextIsSelectable(true);
        licence.setText(readLicence());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        column.addView(licence, lp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private String readLicence() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("LICENSE.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "The licence text could not be loaded. It is available at:\n"
                    + SOURCE_URL + "/blob/main/LICENSE";
        }
        return sb.toString();
    }
}

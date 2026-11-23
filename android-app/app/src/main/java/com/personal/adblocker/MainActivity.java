package com.personal.adblocker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_VPN = 1;
    private static final int MENU_LICENCE = 1;
    private static final String BLOCKED_PREFIX = "BLOCKED  ";

    private TextView status;
    private TextView counts;
    private TextView listInfo;
    private Button toggle;
    private Button update;
    private ArrayAdapter<String> adapter;
    private final List<String> shownNames = new ArrayList<>();
    private List<String> shownRows = new ArrayList<>();
    private boolean updating = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status, matchWidth(0));

        toggle = new Button(this);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, matchWidth(dp(16)));

        counts = new TextView(this);
        counts.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        counts.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(counts, matchWidth(dp(16)));

        update = new Button(this);
        update.setText("Update block list");
        update.setOnClickListener(v -> onUpdate());
        root.addView(update, matchWidth(dp(16)));

        listInfo = new TextView(this);
        listInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        listInfo.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(listInfo, matchWidth(dp(4)));

        TextView heading = new TextView(this);
        heading.setText("Recent lookups: tap one to block or allow it");
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(heading, matchWidth(dp(24)));

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < shownNames.size()) onLookupTapped(shownNames.get(position));
        });
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        fill.topMargin = dp(8);
        root.addView(list, fill);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        if (AdVpnService.blockList == null) reloadListInBackground(null);
        UpdateJobService.schedule(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_LICENCE, Menu.NONE, "Licence and credits");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_LICENCE) {
            startActivity(new Intent(this, LicenceActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void onToggle() {
        if (AdVpnService.running) {
            startService(new Intent(this, AdVpnService.class)
                    .setAction(AdVpnService.ACTION_STOP));
            return;
        }
        // Android shows its own "connection request" dialog the first time.
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN);
        } else {
            startBlocking();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startBlocking();
        }
    }

    private void startBlocking() {
        startForegroundService(new Intent(this, AdVpnService.class)
                .setAction(AdVpnService.ACTION_START));
    }

    /** Downloads the big public lists, then reloads the block list. */
    private void onUpdate() {
        if (updating) return;
        updating = true;
        update.setEnabled(false);
        update.setText("Updating…");
        new Thread(() -> {
            String message;
            try {
                int n = ListUpdater.update(getApplicationContext());
                AdVpnService.reloadList(getApplicationContext());
                message = "Block list updated: " + n + " domains downloaded";
            } catch (IOException | RuntimeException e) {
                message = "Update failed. Check your internet connection and try again.";
            }
            String shown = message;
            runOnUiThread(() -> {
                updating = false;
                update.setEnabled(true);
                update.setText("Update block list");
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "list-update").start();
    }

    private void onLookupTapped(String name) {
        BlockList list = AdVpnService.blockList;
        boolean blocked = list != null && list.isBlocked(name);

        if (blocked) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow this domain?")
                    .setMessage(name + "\n\nIt will no longer be blocked.")
                    .setPositiveButton("Allow", (d, w) -> saveChoice(name, false))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        String site = siteOf(name);
        List<String> choices = new ArrayList<>();
        choices.add(name);
        if (!site.equals(name)) choices.add(site);
        String[] labels = new String[choices.size()];
        labels[0] = "Block only " + name;
        if (labels.length > 1) labels[1] = "Block all of " + site;
        new AlertDialog.Builder(this)
                .setTitle("Block this domain?")
                .setItems(labels, (d, which) -> saveChoice(choices.get(which), true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveChoice(String domain, boolean block) {
        BlockList.setCustom(this, domain, block);
        reloadListInBackground((block ? "Blocked " : "Allowed ") + domain);
    }

    private void reloadListInBackground(String toast) {
        new Thread(() -> {
            AdVpnService.reloadList(getApplicationContext());
            runOnUiThread(() -> {
                if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
                refresh();
            });
        }, "list-reload").start();
    }

    /** The main site of a name: "a.b.example.com" gives "example.com", "x.site.co.in" gives "site.co.in". */
    private static String siteOf(String name) {
        String[] p = name.split("\\.");
        if (p.length <= 2) return name;
        String second = p[p.length - 2];
        boolean twoPartSuffix = p[p.length - 1].length() == 2
                && (second.equals("co") || second.equals("com") || second.equals("net")
                || second.equals("org") || second.equals("gov") || second.equals("ac")
                || second.equals("edu"));
        int keep = twoPartSuffix ? 3 : 2;
        if (p.length <= keep) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - keep; i < p.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(p[i]);
        }
        return sb.toString();
    }

    private void refresh() {
        boolean on = AdVpnService.running;
        status.setText(on ? "Ad blocking is ON" : "Ad blocking is OFF");
        toggle.setText(on ? "Turn off" : "Turn on");
        counts.setText("Blocked " + AdVpnService.blockedCount.get()
                + " of " + AdVpnService.totalCount.get() + " lookups");

        BlockList list = AdVpnService.blockList;
        long updated = ListUpdater.lastUpdated(this);
        String when = updated == 0 ? "not downloaded yet"
                : "updated " + DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(updated));
        listInfo.setText(list == null ? "Loading block list…"
                : "Block list: " + list.size() + " domains\n" + when
                + " (updates itself daily)");

        List<AdVpnService.Lookup> lookups = AdVpnService.recentLookups();
        List<String> rows = new ArrayList<>(lookups.size());
        for (AdVpnService.Lookup l : lookups) {
            rows.add(l.blocked ? BLOCKED_PREFIX + l.name : l.name);
        }
        if (!rows.equals(shownRows)) { // only redraw when something changed
            shownRows = rows;
            shownNames.clear();
            for (AdVpnService.Lookup l : lookups) shownNames.add(l.name);
            adapter.clear();
            adapter.addAll(rows);
            adapter.notifyDataSetChanged();
        }
    }

    private LinearLayout.LayoutParams matchWidth(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

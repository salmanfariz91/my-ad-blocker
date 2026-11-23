package com.personal.adblocker;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.io.IOException;

/** Downloads fresh block lists about once a day, whenever the phone has internet. */
public class UpdateJobService extends JobService {
    private static final int JOB_ID = 1;
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    /** Sets up the daily update if it is not already set up. Safe to call repeatedly. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(ONE_DAY_MS)
                .setPersisted(true) // survives a phone restart
                .build();
        try {
            scheduler.schedule(job);
        } catch (RuntimeException e) {
            // The manual "Update block list" button still works.
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean ok;
            try {
                ListUpdater.update(getApplicationContext());
                // Only reload when the list is in use; otherwise it loads on next start.
                if (AdVpnService.blockList != null) {
                    AdVpnService.reloadList(getApplicationContext());
                }
                ok = true;
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            jobFinished(params, !ok); // on failure, Android retries later
        }, "list-auto-update").start();
        return true; // work continues on the thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // interrupted (for example, internet lost): try again later
    }
}

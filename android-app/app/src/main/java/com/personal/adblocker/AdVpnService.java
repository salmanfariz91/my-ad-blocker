package com.personal.adblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A local "VPN" that only receives the phone's DNS lookups. Lookups for ad domains get a
 * "no such domain" answer; everything else is forwarded to a real DNS server. No other
 * traffic passes through this service, and nothing leaves the phone except normal DNS.
 */
public class AdVpnService extends VpnService {
    static final String ACTION_START = "com.personal.adblocker.START";
    static final String ACTION_STOP = "com.personal.adblocker.STOP";

    static volatile boolean running = false;
    static final AtomicLong blockedCount = new AtomicLong();
    static final AtomicLong totalCount = new AtomicLong();
    static volatile BlockList blockList;

    /** One recent DNS lookup and what happened to it. */
    static final class Lookup {
        final String name;
        final boolean blocked;

        Lookup(String name, boolean blocked) {
            this.name = name;
            this.blocked = blocked;
        }
    }

    private static final ArrayDeque<Lookup> recent = new ArrayDeque<>();
    private static final int RECENT_MAX = 80;

    private static final String VPN_ADDRESS = "10.111.222.1";
    private static final String VPN_DNS = "10.111.222.2";
    private static final String[] UPSTREAM_DNS = {"1.1.1.1", "8.8.8.8"};
    private static final int MAX_DNS_SIZE = 4096;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "vpn";

    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private Thread worker;
    private ExecutorService pool;

    /** Most recent lookups first. */
    static List<Lookup> recentLookups() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /** Re-reads the block list from storage. Slow with a big list: call off the main thread. */
    static void reloadList(Context context) {
        blockList = BlockList.load(context.getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        // A null intent or action means the system started us (restart or always-on VPN).
        startVpn();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        stopVpn();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private synchronized void startVpn() {
        if (running) return;

        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException e) {
            // Blocking still works without the notification.
        }

        if (blockList == null) reloadList(this);
        UpdateJobService.schedule(this);

        Builder builder = new Builder()
                .setSession("My Ad Blocker")
                .setMtu(MAX_DNS_SIZE + 64)
                .addAddress(VPN_ADDRESS, 24)
                .addDnsServer(VPN_DNS)
                .addRoute(VPN_DNS, 32)      // only DNS lookups enter the tunnel
                .setMetered(false);
        try {
            // Let IPv6 traffic keep using the normal network.
            builder.allowFamily(OsConstants.AF_INET6);
        } catch (RuntimeException e) {
            // Not fatal.
        }

        try {
            tun = builder.establish();
        } catch (RuntimeException e) {
            tun = null;
        }
        if (tun == null) { // permission not granted, or another always-on VPN is in the way
            stopForeground(true);
            stopSelf();
            return;
        }

        tunOut = new FileOutputStream(tun.getFileDescriptor());
        pool = Executors.newCachedThreadPool();
        running = true;
        worker = new Thread(this::readLoop, "adblock-dns");
        worker.start();
    }

    private synchronized void stopVpn() {
        running = false;
        if (worker != null) {
            try {
                worker.join(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException e) {
                // Already closed.
            }
            tun = null;
        }
        stopForeground(true);
    }

    private void readLoop() {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        byte[] buf = new byte[32767];
        StructPollfd pfd = new StructPollfd();
        pfd.fd = tun.getFileDescriptor();
        pfd.events = (short) OsConstants.POLLIN;
        StructPollfd[] pfds = {pfd};

        while (running) {
            try {
                // Wake up every second so a stop request is noticed.
                if (Os.poll(pfds, 1000) <= 0) continue;
                if ((pfd.revents & OsConstants.POLLIN) == 0) break; // tunnel closed or failed
                int n = in.read(buf);
                if (n > 0) handlePacket(Arrays.copyOf(buf, n));
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) break;
            } catch (IOException e) {
                // Nothing to read after all, or the tunnel is closing; the loop re-checks.
            }
        }
    }

    /** Handles one IPv4 packet from the tunnel. Anything that is not a UDP DNS query is dropped. */
    private void handlePacket(byte[] p) {
        if (p.length < 28 || ((p[0] >> 4) & 0x0F) != 4) return;
        int ihl = (p[0] & 0x0F) * 4;
        if (ihl < 20 || p.length < ihl + 8) return;
        if ((p[9] & 0xFF) != 17) return;                 // not UDP
        if (u16(p, ihl + 2) != 53) return;               // not DNS
        int dnsOff = ihl + 8;
        int dnsLen = Math.min(u16(p, ihl + 4) - 8, p.length - dnsOff);
        if (dnsLen < 12) return;

        byte[] query = Arrays.copyOfRange(p, dnsOff, dnsOff + dnsLen);
        String name = DnsUtil.questionName(query);
        totalCount.incrementAndGet();

        BlockList list = blockList;
        if (name != null && list != null && list.isBlocked(name)) {
            byte[] answer = DnsUtil.blockedResponse(query);
            if (answer != null) {
                blockedCount.incrementAndGet();
                remember(name, true);
                writeReply(p, ihl, answer);
                return;
            }
        }
        if (name != null && !name.isEmpty()) remember(name, false);

        ExecutorService executor = pool;
        if (executor == null) return;
        try {
            executor.execute(() -> forward(p, ihl, query));
        } catch (RejectedExecutionException e) {
            // Shutting down.
        }
    }

    /** Sends the query to a real DNS server outside the tunnel and relays the answer. */
    private void forward(byte[] request, int ihl, byte[] query) {
        for (String server : UPSTREAM_DNS) {
            try (DatagramSocket socket = new DatagramSocket()) {
                if (!protect(socket)) continue; // must bypass the VPN or it would loop
                socket.setSoTimeout(4000);
                socket.send(new DatagramPacket(query, query.length,
                        InetAddress.getByName(server), 53));
                byte[] buf = new byte[MAX_DNS_SIZE];
                DatagramPacket answer = new DatagramPacket(buf, buf.length);
                socket.receive(answer);
                writeReply(request, ihl, Arrays.copyOf(buf, answer.getLength()));
                return;
            } catch (IOException e) {
                // Timed out or unreachable: try the next server.
            }
        }
    }

    /** Wraps a DNS answer in IPv4 + UDP headers addressed back to the asking app. */
    private void writeReply(byte[] request, int ihl, byte[] dns) {
        int len = 20 + 8 + dns.length;
        byte[] r = new byte[len];
        r[0] = 0x45;                                   // IPv4, 20-byte header
        r[2] = (byte) (len >> 8);
        r[3] = (byte) len;
        r[8] = 64;                                     // TTL
        r[9] = 17;                                     // UDP
        System.arraycopy(request, 16, r, 12, 4);       // source = the address that was asked
        System.arraycopy(request, 12, r, 16, 4);       // destination = the asker
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += u16(r, i);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int checksum = ~sum & 0xFFFF;
        r[10] = (byte) (checksum >> 8);
        r[11] = (byte) checksum;

        r[20] = request[ihl + 2];                      // source port = 53
        r[21] = request[ihl + 3];
        r[22] = request[ihl];                          // destination port = asker's port
        r[23] = request[ihl + 1];
        int udpLen = 8 + dns.length;
        r[24] = (byte) (udpLen >> 8);
        r[25] = (byte) udpLen;                         // UDP checksum left 0 (optional in IPv4)
        System.arraycopy(dns, 0, r, 28, dns.length);

        FileOutputStream out = tunOut;
        if (out == null) return;
        try {
            synchronized (out) {
                out.write(r);
            }
        } catch (IOException e) {
            // Tunnel closed while answering.
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void remember(String name, boolean blocked) {
        synchronized (recent) {
            recent.removeIf(l -> l.name.equals(name));
            recent.addFirst(new Lookup(name, blocked));
            while (recent.size() > RECENT_MAX) recent.removeLast();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Ad blocking", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("My Ad Blocker is on")
                .setContentText("Blocking ads and trackers in apps and browsers")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}

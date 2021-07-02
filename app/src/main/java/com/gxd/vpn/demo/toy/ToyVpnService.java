package com.gxd.vpn.demo.toy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import com.gxd.vpn.demo.R;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ToyVpnService extends VpnService implements Handler.Callback {
    public static final String ACTION_CONNECT = "com.example.android.toyvpn.START";
    public static final String ACTION_DISCONNECT = "com.example.android.toyvpn.STOP";
    private static final String TAG = ToyVpnService.class.getSimpleName();
    private final AtomicReference<Thread> mConnectingThread = new AtomicReference<>();
    private final AtomicReference<ConnectionPair> mConnection = new AtomicReference<>();
    private final AtomicInteger mNextConnectionId = new AtomicInteger(1);
    private Handler mHandler;
    private PendingIntent mConfigureIntent;

    @Override
    public void onCreate() {
        // The handler is only used to show messages.
        if (mHandler == null) {
            mHandler = new Handler(this);
        }
        // Create the intent to "configure" the connection (just start ToyVpnClient).
        Intent intent = new Intent(this, ToyVpnActivity.class);
        mConfigureIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        } else {
            connect();
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        disconnect();
    }

    @Override
    public boolean handleMessage(Message message) {
        Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
        if (message.what != R.string.disconnected) {
            updateForegroundNotification(message.what);
        }
        return true;
    }

    private void connect() {
        // Become a foreground service. Background services can be VPN services too,
        // but they can be killed by background check before getting a chance to receive onRevoke().
        updateForegroundNotification(R.string.connecting);
        mHandler.sendEmptyMessage(R.string.connecting);
        // Extract information from the shared preferences.
        final SharedPreferences sp = getSharedPreferences(SpConst.SP_NAME, MODE_PRIVATE);
        final String serverHost = sp.getString(SpConst.SERVER_HOST, "");
        final byte[] sharedSecret = sp.getString(SpConst.SHARED_SECRET, "").getBytes();
        final boolean allow = sp.getBoolean(SpConst.ALLOW, true);
        final Set<String> packageSet = sp.getStringSet(SpConst.PACKAGES, Collections.emptySet());
        final int serverPort = sp.getInt(SpConst.SERVER_PORT, 0);
        final String proxyHost = sp.getString(SpConst.PROXY_HOST, "");
        final int proxyPort = sp.getInt(SpConst.PROXY_PORT, 0);

        ToyVpnConnection vpnConnection = new ToyVpnConnection(
                this,
                mNextConnectionId.getAndIncrement(),
                new ToyVpnConfig(serverHost, serverPort, sharedSecret, proxyHost, proxyPort, allow, packageSet)
        );
        startConnection(vpnConnection);
    }

    private void startConnection(final ToyVpnConnection connection) {
        // Replace any existing connecting thread with the  new one.
        final Thread thread = new Thread(connection, "ToyVpnThread");
        setConnectingThread(thread);
        // Handler to mark as connected once onEstablish is called.
        connection.setConfigureIntent(mConfigureIntent);
        connection.setOnEstablishListener(tunInterface -> {
            mHandler.sendEmptyMessage(R.string.connected);
            mConnectingThread.compareAndSet(thread, null);
            setConnection(new ConnectionPair(thread, tunInterface));
        });
        thread.start();
    }

    private void setConnectingThread(final Thread thread) {
        final Thread oldThread = mConnectingThread.getAndSet(thread);
        if (oldThread == null) {
            return;
        }
        oldThread.interrupt();
    }

    private void setConnection(final ConnectionPair connectionPair) {
        final ConnectionPair oldConnectionPair = mConnection.getAndSet(connectionPair);
        if (oldConnectionPair == null) {
            return;
        }
        try {
            oldConnectionPair.first.interrupt();
            oldConnectionPair.second.close();
        } catch (IOException e) {
            Log.e(TAG, "Closing VPN interface", e);
        }
    }

    private void disconnect() {
        mHandler.sendEmptyMessage(R.string.disconnected);
        setConnectingThread(null);
        setConnection(null);
        stopForeground(true);
    }

    private void updateForegroundNotification(final int message) {
        final String NOTIFICATION_CHANNEL_ID = "ToyVpn";
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT
        );
        mNotificationManager.createNotificationChannel(notificationChannel);
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentText(getString(message))
                .setContentIntent(mConfigureIntent)
                .build();
        startForeground(1, notification);
    }

    private static class ConnectionPair extends Pair<Thread, ParcelFileDescriptor> {
        public ConnectionPair(Thread thread, ParcelFileDescriptor pfd) {
            super(thread, pfd);
        }
    }
}
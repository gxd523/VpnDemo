package com.gxd.vpn.demo.toy;

import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;

public class ToyVpnConnection implements Runnable {
    /**
     * Maximum packet size is constrained by the MTU, which is given as a signed short.
     */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;
    /**
     * Time to wait in between losing the connection and retrying.
     */
    private static final long RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3);
    /**
     * Time between keepalives if there is no traffic at the moment.
     * <p>
     * TODO: don't do this; it's much better to let the connection die and then reconnect when
     * necessary instead of keeping the network hardware up for hours on end in between.
     **/
    private static final long KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    /**
     * Time to wait without receiving any response before assuming the server is gone.
     */
    private static final long RECEIVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);
    /**
     * Time between polling the VPN interface for new traffic, since it's non-blocking.
     * <p>
     * TODO: really don't do this; a blocking read on another thread is much cleaner.
     */
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);
    /**
     * Number of periods of length {@link IDLE_INTERVAL_MS} to wait before declaring the handshake a complete and abject failure.
     * <p>
     * TODO: use a higher-level protocol; hand-rolling is a fun but pointless exercise.
     */
    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;
    private final VpnService mVpnService;
    private final int mConnectionId;
    private final ToyVpnConfig mToyVpnConfig;
    private PendingIntent mConfigureIntent;
    private OnEstablishListener mOnEstablishListener;

    public ToyVpnConnection(final VpnService service, final int connectionId, final ToyVpnConfig toyVpnConfig) {
        mVpnService = service;
        mConnectionId = connectionId;
        this.mToyVpnConfig = toyVpnConfig;
    }

    /**
     * Optionally, set an intent to configure the VPN. This is {@code null} by default.
     */
    public void setConfigureIntent(PendingIntent intent) {
        mConfigureIntent = intent;
    }

    public void setOnEstablishListener(OnEstablishListener listener) {
        mOnEstablishListener = listener;
    }

    @Override
    public void run() {
        try {
            Log.i(getTag(), "Starting");
            // If anything needs to be obtained using the network, get it now.
            // This greatly reduces the complexity of seamless handover, which tries to recreate the tunnel without shutting down everything.
            // In this demo, all we need to know is the server address.
            final SocketAddress serverAddress = new InetSocketAddress(mToyVpnConfig.serverHost, mToyVpnConfig.serverPort);
            // We try to create the tunnel several times.
            // TODO: The better way is to work with ConnectivityManager, trying only when the network is available.
            // Here we just use a counter to keep things simple.
            for (int attempt = 0; attempt < 10; ++attempt) {
                if (run(serverAddress)) {// Reset the counter if we were connected.
                    attempt = 0;
                }
                Thread.sleep(3000);// Sleep for a while. This also checks if we got interrupted.
            }
            Log.i(getTag(), "Giving up");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            Log.e(getTag(), "Connection failed, exiting", e);
        }
    }

    private boolean run(SocketAddress server) throws IOException, InterruptedException, IllegalArgumentException {
        ParcelFileDescriptor fileDescriptor = null;
        boolean connected = false;
        try (DatagramChannel tunnel = DatagramChannel.open()) {// Create a DatagramChannel as the VPN tunnel.
            if (!mVpnService.protect(tunnel.socket())) {// 第二步: Protect the tunnel before connecting to avoid loopback.
                throw new IllegalStateException("Cannot protect the tunnel");
            }
            tunnel.connect(server);// 第三步: Connect to the server. 隧道套接字连接到 VPN 网关
            // For simplicity, we use the same thread for both reading and writing.
            // Here we put the tunnel into non-blocking mode.
            tunnel.configureBlocking(false);
            // Authenticate and configure the virtual network interface.
            fileDescriptor = handshake(tunnel);
            // Now we are connected. Set the flag.
            connected = true;
            // Packets to be sent are queued in this input stream. 别的app的数据包进入我们的VPN app，加密后由隧道套接字发送出去
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            // Packets received need to be written to this output stream.
            FileOutputStream outputStream = new FileOutputStream(fileDescriptor.getFileDescriptor());
            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);
            // Timeouts:
            //   - when data has not been sent in a while, send empty keepalive messages.
            //   - when data has not been received in a while, assume the connection is broken.
            long lastSendTime = System.currentTimeMillis();
            long lastReceiveTime = System.currentTimeMillis();
            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Assume that we did not make any progress in this iteration.
                boolean idle = true;
                // Read the outgoing packet from the input stream.
                int length = inputStream.read(packet.array());
                if (length > 0) {
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length);
                    tunnel.write(packet);
                    packet.clear();
                    // There might be more outgoing packets.
                    idle = false;
                    lastReceiveTime = System.currentTimeMillis();
                }
                // Read the incoming packet from the tunnel.
                length = tunnel.read(packet);
                if (length > 0) {
                    // Ignore control messages, which start with zero.
                    if (packet.get(0) != 0) {
                        // Write the incoming packet to the output stream.
                        outputStream.write(packet.array(), 0, length);
                    }
                    packet.clear();
                    // There might be more incoming packets.
                    idle = false;
                    lastSendTime = System.currentTimeMillis();
                }
                // If we are idle or waiting for the network, sleep for a fraction of time to avoid busy looping.
                if (idle) {
                    Thread.sleep(IDLE_INTERVAL_MS);
                    final long timeNow = System.currentTimeMillis();
                    if (lastSendTime + KEEPALIVE_INTERVAL_MS <= timeNow) {
                        // We are receiving for a long time but not sending.
                        // Send empty control messages.
                        packet.put((byte) 0).limit(1);
                        for (int i = 0; i < 3; ++i) {
                            packet.position(0);
                            tunnel.write(packet);
                        }
                        packet.clear();
                        lastSendTime = timeNow;
                    } else if (lastReceiveTime + RECEIVE_TIMEOUT_MS <= timeNow) {
                        // We are sending for a long time but not receiving.
                        throw new IllegalStateException("Timed out");
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(getTag(), "Cannot use socket", e);
        } finally {
            if (fileDescriptor != null) {
                try {
                    fileDescriptor.close();
                } catch (IOException e) {
                    Log.e(getTag(), "Unable to close interface", e);
                }
            }
        }
        return connected;
    }

    private ParcelFileDescriptor handshake(DatagramChannel tunnel) throws IOException, InterruptedException {
        // To build a secured tunnel, we should perform mutual authentication and exchange session keys for encryption.
        // To keep things simple in this demo, we just send the shared secret in plaintext and wait for the server to send the parameters.
        // Allocate the buffer for handshaking.
        // We have a hardcoded maximum handshake size of 1024 bytes, which should be enough for demo purposes.
        ByteBuffer packet = ByteBuffer.allocate(1024);
        // Control messages always start with zero.
        packet.put((byte) 0).put(mToyVpnConfig.sharedSecret).flip();// 切换读/写模式

        for (int i = 0; i < 3; ++i) {// Send the secret several times in case of packet loss.
            packet.position(0);
            tunnel.write(packet);
        }
        packet.clear();

        for (int i = 0; i < MAX_HANDSHAKE_ATTEMPTS; ++i) {// Wait for the parameters within a limited time.
            Thread.sleep(IDLE_INTERVAL_MS);
            // Normally we should not receive random packets. Check that the first byte is 0 as expected.
            int length = tunnel.read(packet);
            if (length > 0 && packet.get(0) == 0) {
                String trim = new String(packet.array(), 1, length - 1, US_ASCII).trim();
                return configure(trim);
            }
        }
        throw new IOException("Timed out");
    }

    /**
     * 第四步：为 VPN 流量配置新的本地 TUN 接口
     * @param parameters 从隧道接口(DatagramChannel)读到的数据
     */
    private ParcelFileDescriptor configure(String parameters) throws IllegalArgumentException {
        VpnService.Builder localTunnel = mVpnService.new Builder();// Configure a localTunnel while parsing the parameters.
        for (String parameter : parameters.split(" ")) {
            String[] fields = parameter.split(",");
            try {
                switch (fields[0].charAt(0)) {
                    case 'm':
                        localTunnel.setMtu(Short.parseShort(fields[1]));
                        break;
                    case 'a':
                        // 添加至少一个 IPv4 或 IPv6 地址以及系统指定为本地 TUN 接口地址的子网掩码。
                        // 您的应用通常会在握手过程中收到来自 VPN 网关的 IP 地址和子网掩码。
                        localTunnel.addAddress(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case 'r':
                        // 如果您希望系统通过 VPN 接口发送流量，请至少添加一个路由。
                        // 路由按目标地址过滤。要接受所有流量，请设置开放路由，例如 0.0.0.0/0 或 ::/0
                        localTunnel.addRoute(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case 'd':
                        localTunnel.addDnsServer(fields[1]);
                        break;
                    case 's':
                        localTunnel.addSearchDomain(fields[1]);
                        break;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad parameter: " + parameter);
            }
        }

        final ParcelFileDescriptor fileDescriptor;// Create a new interface using the localTunnel and save the parameters.
        for (String packageName : mToyVpnConfig.packageSet) {
            try {
                if (mToyVpnConfig.allow) {
                    localTunnel.addAllowedApplication(packageName);
                } else {
                    localTunnel.addDisallowedApplication(packageName);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(getTag(), "Package not available: " + packageName, e);
            }
        }
        localTunnel.setSession(mToyVpnConfig.serverHost).setConfigureIntent(mConfigureIntent);
        if (!TextUtils.isEmpty(mToyVpnConfig.proxyHost)) {
            localTunnel.setHttpProxy(ProxyInfo.buildDirectProxy(mToyVpnConfig.proxyHost, mToyVpnConfig.proxyPort));
        }
        synchronized (mVpnService) {
            fileDescriptor = localTunnel.establish();// 系统建立本地 TUN 接口并开始通过该接口传送流量
            if (mOnEstablishListener != null) {
                mOnEstablishListener.onEstablish(fileDescriptor);
            }
        }
        Log.i(getTag(), "New interface: " + fileDescriptor + " (" + parameters + ")");
        return fileDescriptor;
    }

    private String getTag() {
        return ToyVpnConnection.class.getSimpleName() + "[" + mConnectionId + "]";
    }

    /**
     * Callback interface to let the {@link ToyVpnService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor fileDescriptor);
    }
}
package com.gxd.vpn.demo.toy;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.gxd.vpn.demo.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ToyVpnActivity extends Activity {
    private TextView proxyHostTv;
    private TextView proxyPortTv;
    private TextView packagesTv;
    private TextView serverPortTv;
    private TextView serverHostTv;
    private SharedPreferences sp;
    private TextView sharedSecretTv;
    private RadioButton allowedRb;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toy_vpn);

        serverHostTv = findViewById(R.id.address);
        serverPortTv = findViewById(R.id.port);
        sharedSecretTv = findViewById(R.id.secret);
        proxyHostTv = findViewById(R.id.proxyhost);
        proxyPortTv = findViewById(R.id.proxyport);
        packagesTv = findViewById(R.id.packages);
        allowedRb = findViewById(R.id.allowed);

        sp = getSharedPreferences(SpConst.SP_NAME, MODE_PRIVATE);

        serverHostTv.setText(sp.getString(SpConst.SERVER_HOST, ""));
        int serverPort = sp.getInt(SpConst.SERVER_PORT, 0);
        serverPortTv.setText(String.valueOf(serverPort == 0 ? "" : serverPort));
        sharedSecretTv.setText(sp.getString(SpConst.SHARED_SECRET, ""));
        proxyHostTv.setText(sp.getString(SpConst.PROXY_HOST, ""));
        int proxyPort = sp.getInt(SpConst.PROXY_PORT, 0);
        proxyPortTv.setText(proxyPort == 0 ? "" : String.valueOf(proxyPort));
        allowedRb.setChecked(sp.getBoolean(SpConst.ALLOW, true));

        packagesTv.setText(String.join(", ", sp.getStringSet(SpConst.PACKAGES, Collections.emptySet())));
    }

    public void onConnectClick(View view) {
        if (!checkProxyConfigs(proxyHostTv.getText().toString(), proxyPortTv.getText().toString())) {
            return;
        }
        String[] packageArray = packagesTv.getText().toString().split(",");
        final Set<String> packageSet = Arrays.stream(packageArray)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (!checkPackages(packageSet)) {
            return;
        }
        int serverPort;
        try {
            serverPort = Integer.parseInt(serverPortTv.getText().toString());
        } catch (NumberFormatException e) {
            serverPort = 0;
        }
        int proxyPort;
        try {
            proxyPort = Integer.parseInt(proxyPortTv.getText().toString());
        } catch (NumberFormatException e) {
            proxyPort = 0;
        }
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(SpConst.SERVER_HOST, serverHostTv.getText().toString())
                .putInt(SpConst.SERVER_PORT, serverPort)
                .putString(SpConst.SHARED_SECRET, sharedSecretTv.getText().toString())
                .putString(SpConst.PROXY_HOST, proxyHostTv.getText().toString())
                .putInt(SpConst.PROXY_PORT, proxyPort)
                .putBoolean(SpConst.ALLOW, allowedRb.isChecked())
                .putStringSet(SpConst.PACKAGES, packageSet)
                .apply();

        Intent intent = VpnService.prepare(ToyVpnActivity.this);
        if (intent != null) {
            startActivityForResult(intent, 0);
        } else {
            onActivityResult(0, RESULT_OK, null);
        }
    }

    public void onDisconnectClick(View view) {
        Intent intent = new Intent(this, ToyVpnService.class);
        startService(intent.setAction(ToyVpnService.ACTION_DISCONNECT));
    }

    /**
     * proxyHost、proxyPort要同时设置
     */
    private boolean checkProxyConfigs(String proxyHost, String proxyPort) {
        final boolean hasIncompleteProxyConfigs = proxyHost.isEmpty() != proxyPort.isEmpty();
        if (hasIncompleteProxyConfigs) {
            Toast.makeText(this, R.string.incomplete_proxy_settings, Toast.LENGTH_SHORT).show();
        }
        return !hasIncompleteProxyConfigs;
    }

    /**
     * 要填写存在的app的包名
     */
    private boolean checkPackages(Set<String> packageNames) {
        List<PackageInfo> packageInfoList = getPackageManager().getInstalledPackages(0);
        boolean contains = packageInfoList.stream()
                .map(pi -> pi.packageName)
                .collect(Collectors.toSet())
                .containsAll(packageNames);
        final boolean hasCorrectPackageNames = packageNames.isEmpty() || contains;
        if (!hasCorrectPackageNames) {
            Toast.makeText(this, R.string.unknown_package_names, Toast.LENGTH_SHORT).show();
        }
        return hasCorrectPackageNames;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            Intent intent = new Intent(this, ToyVpnService.class);
            startService(intent.setAction(ToyVpnService.ACTION_CONNECT));
        }
    }
}
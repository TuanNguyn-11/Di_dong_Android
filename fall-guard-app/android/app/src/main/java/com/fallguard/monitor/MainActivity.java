package com.fallguard.monitor;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugin nằm ngay trong project này (không phải plugin npm) nên phải tự
        // đăng ký TRƯỚC super.onCreate(), lúc Capacitor dựng cầu nối WebView.
        registerPlugin(FallGuardSensorPlugin.class);
        super.onCreate(savedInstanceState);
    }
}

package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** User-enabled, window-event-only automation; it cannot read window contents or intercept remote keys. */
public final class AppAutomationService extends AccessibilityService {
    static final String VPN_KEY = "youtube_auto_tunnel";
    static final String CLEANUP_KEY = "background_cleanup_5min";
    /** Actual same-process service connection, distinct from a saved setting. */
    static volatile boolean bound;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AppAutomationPolicy policy;
    private Set<String> youtubeApps;
    private boolean receiverRegistered, autoVpn, cleanup, connected;
    private final Runnable cleanupDue = this::cleanupDue;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChange = (preferences, key) -> {
        if (VPN_KEY.equals(key) || CLEANUP_KEY.equals(key)) configure();
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                policy.foreground("", SystemClock.elapsedRealtime(), cleanup);
                sendVpnCommand(policy.vpnCommand(false));
                handler.removeCallbacks(cleanupDue);
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        bound = true;
        youtubeApps = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.youtube_vpn_apps)));
        policy = new AppAutomationPolicy(youtubeApps);
        prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(preferenceChange);
        configure();
    }

    private void configure() {
        if (!connected) return;
        autoVpn = prefs.getBoolean(VPN_KEY, false);
        cleanup = Build.VERSION.SDK_INT < 34 && prefs.getBoolean(CLEANUP_KEY, false);
        if (!cleanup) {
            policy.cancelCleanup();
            handler.removeCallbacks(cleanupDue);
        }
        sendVpnCommand(policy.vpnCommand(autoVpn));
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = autoVpn || cleanup ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0;
            setServiceInfo(info);
        }
        if ((autoVpn || cleanup) && !receiverRegistered) {
            registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            receiverRegistered = true;
        } else if (!autoVpn && !cleanup && receiverRegistered) {
            unregisterReceiver(screen);
            receiverRegistered = false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if ((!autoVpn && !cleanup) || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null || event.getClassName() == null) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null || !power.isInteractive()) return;
        String pkg = event.getPackageName().toString();
        String cls = event.getClassName().toString();
        try {
            if (HappControl.commandWindow(this, pkg, cls)) return;
            // Toasts, volume panels, keyboards and dialogs do not mean the user left the video app.
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return;
        }
        policy.foreground(pkg, SystemClock.elapsedRealtime(), cleanup);
        sendVpnCommand(policy.vpnCommand(autoVpn));
        cleanupDue();
    }

    private void sendVpnCommand(int command) {
        if (command == 0) return;
        try {
            HappControl.send(this, command);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.automation_happ_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void cleanupDue() {
        handler.removeCallbacks(cleanupDue);
        if (!cleanup) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null || !power.isInteractive()) return;
        long now = SystemClock.elapsedRealtime();
        for (String pkg : policy.takeDue(now)) {
            if (!mayRelease(pkg)) continue;
            try {
                // Best effort on Android 8-13. This neither force-stops packages nor removes their recent tasks.
                ActivityManager manager = getSystemService(ActivityManager.class);
                if (manager != null) manager.killBackgroundProcesses(pkg);
            } catch (RuntimeException ignored) {
                // Never claim that a request guaranteed an app was stopped.
            }
        }
        long next = policy.nextDeadline();
        if (next != Long.MAX_VALUE) handler.postDelayed(cleanupDue, Math.max(1, next - now));
    }

    private boolean mayRelease(String pkg) {
        if (pkg.isEmpty() || pkg.equals(getPackageName()) || HappControl.isHapp(pkg)
                || pkg.equals("android") || pkg.startsWith("com.android.")
                || pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.gsf")
                || pkg.equals("com.android.vending")) return false;
        PackageManager pm = getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES);
            ApplicationInfo app = info.applicationInfo;
            if (app == null || app.uid < 10000 || (app.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) return false;
            // Preinstalled system packages are excluded; YouTube is an explicit user-requested exception.
            if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    && !youtubeApps.contains(pkg)) return false;
            String[] shared = pm.getPackagesForUid(app.uid);
            if (shared == null || shared.length != 1) return false;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) return false;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
            if (!pm.queryIntentActivities(home, 0).isEmpty()) return false;
            if (info.services != null) for (ServiceInfo service : info.services) {
                String permission = service.permission;
                if ("android.permission.BIND_VPN_SERVICE".equals(permission)
                        || "android.permission.BIND_ACCESSIBILITY_SERVICE".equals(permission)
                        || "android.permission.BIND_INPUT_METHOD".equals(permission)
                        || "android.permission.BIND_TV_INPUT".equals(permission)) return false;
            }
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        stopAutomation();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stopAutomation();
        super.onDestroy();
    }

    private void stopAutomation() {
        connected = autoVpn = cleanup = false;
        bound = false;
        handler.removeCallbacksAndMessages(null);
        if (policy != null) {
            sendVpnCommand(policy.vpnCommand(false));
            policy.cancelCleanup();
        }
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(preferenceChange);
        if (receiverRegistered) unregisterReceiver(screen);
        receiverRegistered = false;
    }
}

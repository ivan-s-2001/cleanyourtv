package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-chosen launchable apps; only explicit Save changes tunnel automation. */
final class VpnAppsPicker {
    static final String KEY = "auto_vpn_apps";
    private final Activity activity;
    private final Runnable saved;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private boolean loading, closed;
    private AlertDialog dialog;

    static Set<String> defaults(Context context) {
        return new HashSet<>(Arrays.asList(context.getResources().getStringArray(R.array.youtube_vpn_apps)));
    }

    static Set<String> selected(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Set<String> stored = prefs.getStringSet(KEY, null);
        Set<String> result = stored == null ? defaults(context) : new HashSet<>(stored);
        result.remove(context.getPackageName());
        result.removeIf(HappControl::isHapp);
        return result;
    }

    VpnAppsPicker(Activity activity, Runnable saved) {
        this.activity = activity;
        this.saved = saved;
    }

    void show() {
        if (closed || loading || dialog != null && dialog.isShowing()) return;
        loading = true;
        Toast.makeText(activity, R.string.vpn_apps_loading, Toast.LENGTH_SHORT).show();
        Set<String> selected = selected(activity);
        loader.execute(() -> {
            try {
                PackageManager pm = activity.getPackageManager();
                Map<String, String> labels = new HashMap<>();
                for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                    for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (info.activityInfo == null || !info.activityInfo.exported) continue;
                        String pkg = info.activityInfo.packageName;
                        if (pkg.equals(activity.getPackageName()) || HappControl.isHapp(pkg) || labels.containsKey(pkg)) continue;
                        try { labels.put(pkg, Cards.cleanLabel(String.valueOf(info.loadLabel(pm)), pkg)); }
                        catch (RuntimeException unavailable) { labels.put(pkg, pkg); }
                    }
                }
                Collator collator = Collator.getInstance();
                List<String> packages = new ArrayList<>(labels.keySet());
                packages.sort((a, b) -> {
                    int order = collator.compare(labels.get(a), labels.get(b));
                    return order != 0 ? order : a.compareTo(b);
                });
                main.post(() -> {
                    loading = false;
                    if (closed || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
                    if (packages.isEmpty()) {
                        Toast.makeText(activity, R.string.library_empty, Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] names = new String[packages.size()];
                    boolean[] checked = new boolean[names.length];
                    for (int i = 0; i < names.length; i++) {
                        names[i] = labels.get(packages.get(i));
                        checked[i] = selected.contains(packages.get(i));
                    }
                    dialog = new AlertDialog.Builder(activity).setTitle(R.string.vpn_apps_title)
                            .setMultiChoiceItems(names, checked, (d, which, enabled) -> checked[which] = enabled)
                            .setPositiveButton(android.R.string.ok, (d, which) -> {
                                Set<String> chosen = new HashSet<>();
                                for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(packages.get(i));
                                save(chosen);
                            })
                            .setNeutralButton(R.string.vpn_apps_reset, (d, which) -> save(defaults(activity)))
                            .setNegativeButton(android.R.string.cancel, null).create();
                    dialog.show();
                });
            } catch (RuntimeException unavailable) {
                main.post(() -> {
                    loading = false;
                    if (!closed && !activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus())
                        Toast.makeText(activity, R.string.not_available, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void save(Set<String> chosen) {
        SharedPreferences.Editor edit = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, chosen);
        if (chosen.isEmpty()) edit.putBoolean(AppAutomationService.VPN_KEY, false);
        edit.apply();
        saved.run();
    }

    void close() {
        closed = true;
        loader.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (dialog != null) dialog.dismiss();
    }
}

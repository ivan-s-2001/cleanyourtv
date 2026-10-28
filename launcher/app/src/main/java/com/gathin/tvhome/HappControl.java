package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

/** Both official distribution identities. No implicit dispatch to an arbitrary happ:// handler. */
final class HappControl {
    private static final String[] PACKAGES = {"com.happproxy", "su.happ.proxyutility"};

    static boolean isHapp(String pkg) {
        for (String candidate : PACKAGES) if (candidate.equals(pkg)) return true;
        return false;
    }

    static Intent resolve(Context context, String uri) {
        for (String pkg : PACKAGES) {
            Intent intent = resolveForPackage(context, uri, pkg);
            if (intent != null) return intent;
        }
        return null;
    }

    private static Intent resolveForPackage(Context context, String uri, String pkg) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
        ResolveInfo match = context.getPackageManager().resolveActivity(intent, 0);
        return match != null && match.activityInfo != null && match.activityInfo.exported
                && pkg.equals(match.activityInfo.packageName)
                ? intent.setComponent(new ComponentName(pkg, match.activityInfo.name)) : null;
    }

    private static Intent command(Context context, int direction) {
        for (String pkg : PACKAGES) {
            Intent on = resolveForPackage(context, "happ://connect_without_ui", pkg);
            Intent off = resolveForPackage(context, "happ://disconnect_without_ui", pkg);
            if (on != null && off != null) return direction > 0 ? on : off;
        }
        return null;
    }

    static boolean supported(Context context) { return command(context, 1) != null; }

    static boolean commandWindow(Context context, String pkg, String className) {
        if (!isHapp(pkg)) return false;
        for (String command : new String[]{"connect_without_ui", "disconnect_without_ui"}) {
            Intent intent = resolveForPackage(context, "happ://" + command, pkg);
            if (intent != null && intent.getComponent().equals(new ComponentName(pkg, className))) return true;
        }
        return false;
    }

    static void send(Context context, int command) {
        Intent intent = command(context, command);
        if (intent == null) throw new android.content.ActivityNotFoundException();
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
    }
}

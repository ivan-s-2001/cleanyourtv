package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets tools/theme.sh write the theme files into {@link Config#dir} on firmware where the adb shell is
 * locked out of Android/data (TCL G07, Android 11). Only the files {@link ThemeFiles} allows, and only for
 * callers holding WRITE_SECURE_SETTINGS, which the adb shell has and ordinary apps cannot get.
 *
 *   content write --uri content://com.gathin.tvhome.theme/config.json < /data/local/tmp/config.json
 *   content write --uri content://com.gathin.tvhome.theme/wallpapers/dusk.jpg < /data/local/tmp/dusk.jpg
 *   content delete --uri content://com.gathin.tvhome.theme/config.json
 *   content delete --uri content://com.gathin.tvhome.theme/wallpapers      (every image in the collection)
 */
public class ThemeProvider extends ContentProvider {

    /** Explicit shell setup for the personal fork; it cannot grant any Android/system permission. */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"configure_features".equals(method)) return super.call(method, arg, extras);
        Context context = getContext();
        // ContentProvider.call has no URI permission enforcement: check the caller before reading/writing prefs.
        context.enforceCallingPermission("android.permission.WRITE_SECURE_SETTINGS", "Shell setup only");
        Bundle answer = new Bundle();
        String[] keys = {WatchShelf.KEY, ShelfImages.NETWORK_KEY,
                AppAutomationService.VPN_KEY, AppAutomationService.CLEANUP_KEY};
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(keys));
        if (extras != null) for (String key : extras.keySet()) {
            if (!allowed.contains(key) || !(extras.get(key) instanceof Boolean)) {
                answer.putString("error", "Only the four boolean feature flags are accepted");
                return answer;
            }
        }
        if (extras != null && extras.getBoolean(WatchShelf.KEY, false)
                && context.checkSelfPermission(WatchRepository.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            answer.putString("error", "Grant READ_TV_LISTINGS first");
            return answer;
        }
        if (extras != null && extras.getBoolean(AppAutomationService.VPN_KEY, false)
                && !HappControl.supported(context)) {
            answer.putString("error", "Install and configure a supported Happ app first");
            return answer;
        }
        if (extras != null && extras.getBoolean(AppAutomationService.VPN_KEY, false)
                && VpnAppsPicker.selected(context).isEmpty()) {
            answer.putString("error", "Select apps for Auto VPN in Launcher settings first");
            return answer;
        }
        if (extras != null && extras.getBoolean(AppAutomationService.CLEANUP_KEY, false)
                && android.os.Build.VERSION.SDK_INT >= 34) {
            answer.putString("error", "Background cleanup is unavailable on Android 14+");
            return answer;
        }
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (extras != null && !extras.isEmpty()) {
            SharedPreferences.Editor edit = prefs.edit();
            for (String key : keys) if (extras.containsKey(key)) edit.putBoolean(key, extras.getBoolean(key));
            edit.putLong(Config.SETTINGS_REVISION_KEY, prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1);
            if (!edit.commit()) {
                answer.putString("error", "Could not save feature settings");
                return answer;
            }
        }
        answer.putBoolean("configured", true);
        answer.putBoolean("happ_supported", HappControl.supported(context));
        answer.putBoolean("service_bound", AppAutomationService.bound);
        for (String key : keys) answer.putBoolean(key,
                prefs.getBoolean(key, key.equals(WatchShelf.KEY) || key.equals(ShelfImages.NETWORK_KEY)));
        return answer;
    }

    private File file(Uri uri) throws FileNotFoundException {
        String path = ThemeFiles.path(uri.getPathSegments());
        File dir = Config.dir(getContext());
        if (dir == null || path == null) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(dir, path);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (mode.contains("w")) {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File dir = Config.dir(getContext());
        if (dir != null && ThemeFiles.isCollection(uri.getPathSegments())) {
            File[] images = new File(dir, ThemeFiles.WALLPAPERS).listFiles((d, name) -> ThemeFiles.collectionImage(name));
            int n = 0;
            if (images != null) for (File f : images) if (f.delete()) n++;
            return n;
        }
        try {
            return file(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}

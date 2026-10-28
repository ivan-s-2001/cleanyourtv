package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.util.Base64;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Imports an ordinary routing profile using Happ's documented deeplink; no VPN credentials are read. */
final class HappSetup {
    static void show(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.happ_youtube_title)
                .setMessage(R.string.happ_youtube_help)
                .setPositiveButton(R.string.happ_youtube_import, (dialog, which) -> {
                    try {
                        StringBuilder json = new StringBuilder();
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                                activity.getResources().openRawResource(R.raw.happ_youtube_only), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = in.readLine()) != null) json.append(line);
                        }
                        String encoded = Base64.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                        Intent intent = HappControl.resolve(activity, "happ://routing/onadd/" + encoded);
                        if (intent == null) throw new android.content.ActivityNotFoundException();
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(activity, R.string.happ_youtube_missing, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

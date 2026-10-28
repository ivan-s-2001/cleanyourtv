package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The focused favourite's public Continue watching / preview rows. Exists only after an explicit opt-in. */
final class WatchShelf {
    static final String KEY = "watch_shelf";
    private final Activity activity;
    private final LinearLayout panel, row;
    private final TextView heading, message;
    private final HorizontalScrollView scroll;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable returnToTile, goToTop;
    private final float density;
    private ExecutorService worker;
    private CancellationSignal cancellation;
    private String pkg;
    private volatile int generation;
    private boolean dirty = true, observing;
    private final Runnable reload = this::load;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            dirty = true;
            // Do not replace the card the person is navigating. Refresh on returning to its favourite.
            if (!panel.hasFocus()) schedule();
        }
    };

    WatchShelf(Activity activity, FrameLayout root, Runnable returnToTile, Runnable goToTop) {
        this.activity = activity;
        this.returnToTile = returnToTile;
        this.goToTop = goToTop;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        lp.leftMargin = dp(68);
        lp.rightMargin = dp(68);
        root.addView(panel, lp);
        heading = text(18);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(28)));
        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(5), dp(5), dp(5), dp(5));
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(148)));
        message = text(15);
        message.setTextColor(0xffc3c9d0);
        panel.addView(message);
    }

    void resume() {
        if (worker != null) return;
        worker = Executors.newSingleThreadExecutor();
        dirty = true;
        try {
            activity.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
            activity.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
            observing = true;
        } catch (RuntimeException ignored) {
            // Provider changes will still be picked up on the next return to Home.
            activity.getContentResolver().unregisterContentObserver(observer);
        }
    }

    void pause() {
        if (observing) activity.getContentResolver().unregisterContentObserver(observer);
        observing = false;
        hide();
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    void show(String packageName, String label, float dockTop) {
        if (worker == null) return;
        panel.setY(Math.max(0, dockTop - dp(206)));
        panel.setVisibility(View.VISIBLE);
        heading.setText(activity.getString(R.string.watch_shelf_heading, label));
        if (!packageName.equals(pkg)) {
            generation++;
            pkg = packageName;
            dirty = true;
            row.removeAllViews();
            scroll.setVisibility(View.GONE);
            message.setVisibility(View.VISIBLE);
            message.setText(R.string.watch_shelf_loading);
        }
        if (dirty) schedule();
    }

    void hide() {
        pkg = null;
        dirty = true;
        generation++;
        main.removeCallbacks(reload);
        if (cancellation != null) cancellation.cancel();
        panel.setVisibility(View.GONE);
        row.removeAllViews();
    }

    boolean contains(View view) {
        while (view != null) {
            if (view == panel) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    boolean hasFocus() { return panel.hasFocus(); }

    boolean focusFirst() {
        return panel.getVisibility() == View.VISIBLE && row.getChildCount() > 0 && row.getChildAt(0).requestFocus();
    }

    private void schedule() {
        main.removeCallbacks(reload);
        main.postDelayed(reload, 180);
    }

    private void load() {
        if (pkg == null || worker == null) return;
        if (panel.hasFocus()) { dirty = true; return; }
        if (cancellation != null) cancellation.cancel();
        CancellationSignal signal = new CancellationSignal();
        cancellation = signal;
        final int token = ++generation;
        final String owner = pkg;
        dirty = false;
        worker.execute(() -> {
            try {
                WatchRepository.Result result = WatchRepository.load(activity.getContentResolver(), owner, signal);
                main.post(() -> {
                    if (token != generation || worker == null || !owner.equals(pkg)) return;
                    if (panel.hasFocus()) { dirty = true; return; }
                    render(owner, result, token);
                });
            } catch (android.os.OperationCanceledException ignored) {
                // Focus changed or Home left the foreground.
            }
        });
    }

    private void render(String owner, WatchRepository.Result result, int token) {
        row.removeAllViews();
        scroll.scrollTo(0, 0);
        boolean empty = result.items.isEmpty();
        scroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        message.setVisibility(empty ? View.VISIBLE : View.GONE);
        message.setText(result.unavailable ? R.string.watch_shelf_unavailable : R.string.watch_shelf_empty);
        for (WatchItem item : result.items) {
            FrameLayout card = new FrameLayout(activity);
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackground(background(false));
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), dp(132));
            lp.rightMargin = dp(12);
            row.addView(card, lp);
            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setAlpha(0.35f);
            card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.BOTTOM);
            card.addView(labels, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = text(11);
            badge.setText(!item.channel.isEmpty() ? item.channel : activity.getString(
                    item.type == 0 ? R.string.watch_continue : item.type == 1 ? R.string.watch_next
                            : item.type == 2 ? R.string.watch_new : item.type == 3 ? R.string.watch_list : R.string.watch_recommended));
            badge.setSingleLine(true);
            labels.addView(badge);
            TextView title = text(15);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setText(item.title);
            labels.addView(title);
            if (!item.episode.isEmpty()) {
                TextView episode = text(11);
                episode.setSingleLine(true);
                episode.setEllipsize(android.text.TextUtils.TruncateAt.END);
                episode.setText(item.episode);
                labels.addView(episode);
            }
            if (item.duration > 0 && item.position > 0) {
                ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(100);
                progress.setProgress(item.progress());
                labels.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
            }
            card.setContentDescription(badge.getText() + ": " + item.title + " " + item.episode);
            card.setOnFocusChangeListener((v, focused) -> card.setBackground(background(focused)));
            card.setOnClickListener(v -> open(owner, item.intentUri));
            card.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) { returnToTile.run(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_UP) { goToTop.run(); return true; }
                return code == KeyEvent.KEYCODE_DPAD_LEFT && row.indexOfChild(card) == 0
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT && row.indexOfChild(card) == row.getChildCount() - 1;
            });
            // Preserve the launcher's offline contract. Network poster URIs use a title card instead.
            Uri uri = Uri.parse(item.posterUri);
            if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                worker.execute(() -> {
                    if (token != generation || Thread.currentThread().isInterrupted()) return;
                    Bitmap bitmap = localPoster(uri);
                    main.post(() -> {
                        if (token == generation && worker != null && bitmap != null) poster.setImageBitmap(bitmap);
                    });
                });
            }
        }
    }

    private Bitmap localPoster(Uri uri) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, options);
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void open(String owner, String intentUri) {
        try {
            Intent intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME | Intent.URI_ANDROID_APP_SCHEME);
            // A provider's card may launch only its own app, without URI grants or a selector overriding the package.
            if (intent.getComponent() != null && !owner.equals(intent.getComponent().getPackageName()))
                intent.setComponent(null);
            intent.setSelector(null);
            intent.setPackage(owner);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setClipData(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(intent);
        } catch (Exception ignored) {
            Toast.makeText(activity, R.string.watch_shelf_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private GradientDrawable background(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xff384654 : 0xec19232d);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(focused ? 2 : 1), focused ? Color.WHITE : 0x405e6b78);
        return bg;
    }

    private TextView text(int size) {
        TextView view = new TextView(activity);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }
}

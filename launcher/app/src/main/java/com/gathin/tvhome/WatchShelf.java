package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cinematic poster shelf inspired by rom4ikk1/cleanyourtv-ru; data stays owned by the selected app. */
final class WatchShelf {
    static final String KEY = "watch_shelf";
    private final Activity activity;
    private final FrameLayout root;
    private final LinearLayout panel, row, details, body;
    private final TextView heading, message, title, metadata, description;
    private final HorizontalScrollView scroll;
    private final ImageView backdrop;
    private final View shade;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable returnToTile, goToTop;
    private final float density;
    private ExecutorService worker;
    private ShelfImages images;
    private CancellationSignal cancellation;
    private String pkg, appLabel = "";
    private volatile int generation;
    private boolean dirty = true, observing, changingFocus;
    private int sectionIndex, cardHeight;
    private List<WatchSection> sections = Collections.emptyList();
    private WatchItem described;
    private final Runnable reload = this::load;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            dirty = true;
            // Never replace the card under the user's remote.
            if (!panel.hasFocus()) schedule();
        }
    };

    static boolean enabled(Activity activity) {
        return activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE).getBoolean(KEY, true)
                && activity.checkSelfPermission(WatchRepository.PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    WatchShelf(Activity activity, FrameLayout root, Runnable returnToTile, Runnable goToTop) {
        this.activity = activity;
        this.root = root;
        this.returnToTile = returnToTile;
        this.goToTop = goToTop;
        density = activity.getResources().getDisplayMetrics().density;
        backdrop = new ImageView(activity);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.5f);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        backdrop.setVisibility(View.GONE);
        shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xf50a111b, 0xc50a111b, 0x80202b38}));
        shade.setVisibility(View.GONE);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int below = root.indexOfChild(root.findViewById(R.id.ambient));
        root.addView(backdrop, Math.max(0, below), new FrameLayout.LayoutParams(-1, -1));
        root.addView(shade, Math.max(0, below) + 1, new FrameLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(216));
        lp.leftMargin = dp(60);
        lp.rightMargin = dp(60);
        root.addView(panel, lp);
        heading = text(15);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setTextColor(0xffd7e2ed);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(26)));
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(0, dp(6), dp(28), dp(6));
        body.addView(details, new LinearLayout.LayoutParams(0, -1, 0.37f));
        title = text(27);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(title);
        metadata = text(13);
        metadata.setTextColor(0xffbdd4e9);
        metadata.setPadding(0, dp(6), 0, dp(8));
        details.addView(metadata);
        description = text(13);
        description.setTextColor(0xffc7d0db);
        description.setMaxLines(4);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(description);
        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(5), dp(4), dp(5), dp(4));
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        body.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.63f));
        message = text(15);
        message.setTextColor(0xffc3c9d0);
        panel.addView(message);
    }

    void resume() {
        if (worker != null) return;
        worker = Executors.newSingleThreadExecutor();
        images = new ShelfImages(activity);
        dirty = true;
        try {
            activity.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
            activity.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
            activity.getContentResolver().registerContentObserver(TvContract.Channels.CONTENT_URI, true, observer);
            observing = true;
        } catch (RuntimeException ignored) {
            try { activity.getContentResolver().unregisterContentObserver(observer); } catch (RuntimeException unused) { }
        }
    }

    void pause() {
        if (observing) activity.getContentResolver().unregisterContentObserver(observer);
        observing = false;
        hide();
        if (worker != null) worker.shutdownNow();
        worker = null;
        if (images != null) images.close();
        images = null;
    }

    void show(String packageName, String label, float dockTop) {
        if (worker == null) return;
        // Fit below the clock, including 720p TVs; keep the dock reachable on short screens.
        Rect header = new Rect();
        View clock = root.findViewById(R.id.leftBlock);
        clock.getDrawingRect(header);
        root.offsetDescendantRectToMyCoords(clock, header);
        int height = Math.min(dp(216), Math.max(dp(96), (int) dockTop - header.bottom - dp(22)));
        cardHeight = Math.max(dp(52), height - dp(38));
        panel.getLayoutParams().height = height;
        panel.requestLayout();
        panel.setY(Math.max(0, dockTop - height - dp(16)));
        details.setVisibility(height < dp(150) ? View.GONE : View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
        appLabel = label;
        if (!packageName.equals(pkg)) {
            generation++;
            pkg = packageName;
            sections = Collections.emptyList();
            described = null;
            dirty = true;
            clearPictures();
            row.removeAllViews();
            body.setVisibility(View.GONE);
            heading.setText(label);
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
        changingFocus = true;
        try { row.removeAllViews(); } finally { changingFocus = false; }
        sections = Collections.emptyList();
        described = null;
        clearPictures();
    }

    private void clearPictures() {
        if (images != null) images.cancel();
        backdrop.setImageDrawable(null);
        backdrop.setVisibility(View.GONE);
        shade.setVisibility(View.GONE);
    }

    boolean contains(View view) {
        while (view != null) {
            if (view == panel) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    boolean hasFocus() { return panel.hasFocus(); }

    boolean managesFocus() { return changingFocus; }

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
                    sections = WatchSection.group(result.items);
                    sectionIndex = 0;
                    boolean empty = sections.isEmpty();
                    body.setVisibility(empty ? View.GONE : View.VISIBLE);
                    message.setVisibility(empty ? View.VISIBLE : View.GONE);
                    message.setText(result.unavailable ? R.string.watch_shelf_unavailable : R.string.watch_shelf_empty);
                    if (!empty) render();
                });
            } catch (android.os.OperationCanceledException ignored) { }
        });
    }

    private String sectionTitle(WatchSection section) {
        if (!section.channel.isEmpty()) return section.channel;
        return activity.getString(section.kind == 0 ? R.string.watch_continue : section.kind == 2
                ? R.string.watch_new : section.kind == 3 ? R.string.watch_list : R.string.watch_recommended);
    }

    private void render() {
        clearPictures();
        row.removeAllViews();
        scroll.scrollTo(0, 0);
        WatchSection section = sections.get(sectionIndex);
        String name = appLabel + " · " + sectionTitle(section);
        heading.setText(sections.size() > 1 ? activity.getString(R.string.watch_section_nav,
                name, sectionIndex + 1, sections.size()) : name);
        final String owner = pkg;
        describe(section.items.get(0));
        for (WatchItem item : section.items) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackground(background(false));
            card.setPadding(dp(4), dp(4), dp(4), dp(4));
            int width = Math.max(dp(64), (cardHeight - dp(28)) * 2 / 3);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, cardHeight);
            lp.rightMargin = dp(12);
            row.addView(card, lp);
            FrameLayout art = new FrameLayout(activity);
            art.setBackgroundColor(0xff223242);
            card.addView(art, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView fallback = text(14);
            fallback.setText(item.title);
            fallback.setMaxLines(4);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(6), dp(4), dp(6), dp(4));
            art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            TextView caption = text(12);
            caption.setText(item.title);
            caption.setSingleLine(true);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            caption.setPadding(dp(2), dp(4), dp(2), 0);
            card.addView(caption, new LinearLayout.LayoutParams(-1, dp(23)));
            if (item.duration > 0 && item.position > 0) {
                ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(100);
                progress.setProgress(item.progress());
                card.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
            }
            card.setContentDescription(sectionTitle(section) + ": " + item.title + " " + item.episode);
            card.setOnFocusChangeListener((v, focused) -> {
                card.setBackground(background(focused));
                if (focused) describe(item);
            });
            card.setOnClickListener(v -> open(owner, item.intentUri));
            card.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (sectionIndex + 1 < sections.size()) switchSection(sectionIndex + 1);
                    else goToTop.run();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (sectionIndex > 0) switchSection(sectionIndex - 1);
                    else returnToTile.run();
                    return true;
                }
                return code == KeyEvent.KEYCODE_DPAD_LEFT && row.indexOfChild(card) == 0
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT && row.indexOfChild(card) == row.getChildCount() - 1;
            });
            images.load(item.posterUri, false, bitmap -> {
                poster.setImageBitmap(bitmap);
                fallback.setVisibility(View.GONE);
            });
        }
    }

    private void switchSection(int to) {
        // The provider is not re-read just because the user changes rows.
        changingFocus = true;
        try {
            sectionIndex = to;
            render();
            focusFirst();
        } finally { changingFocus = false; }
    }

    private void describe(WatchItem item) {
        described = item;
        title.setText(item.title);
        String meta = item.metadata;
        if (!item.episode.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + item.episode;
        metadata.setText(meta);
        metadata.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        description.setText(item.description);
        backdrop.setImageDrawable(null);
        backdrop.setVisibility(View.GONE);
        shade.setVisibility(View.GONE);
        String picture = item.backdropUri.isEmpty() ? item.posterUri : item.backdropUri;
        images.load(picture, true, bitmap -> {
            if (described != item || pkg == null) return;
            backdrop.setImageBitmap(bitmap);
            backdrop.setVisibility(View.VISIBLE);
            shade.setVisibility(View.VISIBLE);
        });
    }

    private void open(String owner, String intentUri) {
        try {
            Intent intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME | Intent.URI_ANDROID_APP_SCHEME);
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
        bg.setColor(focused ? 0xff354e68 : 0xe6172330);
        bg.setCornerRadius(dp(8));
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

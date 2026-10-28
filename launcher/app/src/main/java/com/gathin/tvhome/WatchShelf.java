package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Published viewing rows using the launcher's own type, tile shapes and glass focus. */
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
    private final Config config;
    private ExecutorService worker;
    private ShelfImages images;
    private CancellationSignal cancellation;
    private String pkg, appLabel = "";
    private volatile int generation;
    private boolean dirty = true, observing, changingFocus, compact;
    private int sectionIndex, cardHeight;
    private final Map<Integer, Integer> selectedCards = new HashMap<>();
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

    WatchShelf(Activity activity, FrameLayout root, Config config, Runnable returnToTile, Runnable goToTop) {
        this.activity = activity;
        this.root = root;
        this.config = config;
        this.returnToTile = returnToTile;
        this.goToTop = goToTop;
        density = activity.getResources().getDisplayMetrics().density;
        backdrop = new ImageView(activity);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.22f);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        backdrop.setVisibility(View.GONE);
        shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{tintBackground(238), tintBackground(170), tintBackground(70)}));
        shade.setVisibility(View.GONE);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int below = root.indexOfChild(root.findViewById(R.id.ambient));
        root.addView(backdrop, Math.max(0, below), new FrameLayout.LayoutParams(-1, -1));
        root.addView(shade, Math.max(0, below) + 1, new FrameLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(216));
        // Match heroName and the main shelf, rather than introduce a separate inset/palette.
        lp.leftMargin = dp(68);
        lp.rightMargin = dp(68);
        root.addView(panel, lp);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        heading = text(15);
        heading.setTypeface(UiFont.get(activity, R.string.font_medium));
        heading.setLetterSpacing(0.02f);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setTextColor(0xb3ffffff);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(30)));
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setClipChildren(false);
        panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(0, dp(6), dp(28), dp(6));
        body.addView(details, new LinearLayout.LayoutParams(0, -1, 0.37f));
        title = text(30);
        title.setTypeface(UiFont.get(activity, R.string.font_light));
        title.setLetterSpacing(-0.02f);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(title);
        metadata = text(13);
        metadata.setTypeface(UiFont.get(activity, R.string.font_light));
        metadata.setTextColor(0xb3ffffff);
        metadata.setPadding(0, dp(6), 0, dp(8));
        details.addView(metadata);
        description = text(13);
        description.setTextColor(0x99ffffff);
        description.setMaxLines(4);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(description);
        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding(dp(8), dp(8), dp(16), dp(8));
        scroll.setSmoothScrollingEnabled(config.animations && !config.reducedMotion);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.setFocusable(false);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.addView(row);
        body.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.63f));
        message = text(14);
        message.setTextColor(0x99ffffff);
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
        // Leave air on both sides of the shelf, rather than touching the clock or dock.
        int room = (int) dockTop - header.bottom - dp(24 + 24);
        if (room < dp(76)) { hide(); return; }
        int height = Math.min(dp(216), room);
        boolean wasCompact = compact;
        int previousCardHeight = cardHeight;
        compact = height < dp(150);
        heading.getLayoutParams().height = dp(compact ? 22 : 30);
        cardHeight = height - dp(compact ? 38 : 46);
        panel.getLayoutParams().height = height;
        panel.requestLayout();
        panel.setY(Math.max(header.bottom + dp(24), dockTop - height - dp(24)));
        details.setVisibility(compact ? View.GONE : View.VISIBLE);
        title.setTextSize(height < dp(190) ? 24 : 30);
        description.setMaxLines(height < dp(190) ? 2 : 4);
        panel.setVisibility(View.VISIBLE);
        if (!panel.hasFocus()) clearBackdrop();
        appLabel = label;
        if (!packageName.equals(pkg)) {
            generation++;
            pkg = packageName;
            sections = Collections.emptyList();
            selectedCards.clear();
            sectionIndex = 0;
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
        else if (!sections.isEmpty() && (compact != wasCompact || cardHeight != previousCardHeight)) render();
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
        clearBackdrop();
    }

    private void clearBackdrop() {
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
        if (panel.getVisibility() != View.VISIBLE || row.getChildCount() == 0) return false;
        int index = Math.min(selectedCards.getOrDefault(sectionIndex, 0), row.getChildCount() - 1);
        return row.getChildAt(index).requestFocus();
    }

    void topBarFocused() { clearBackdrop(); }

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
                    selectedCards.clear();
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
        describe(section.items.get(Math.min(selectedCards.getOrDefault(sectionIndex, 0), section.items.size() - 1)));
        for (WatchItem item : section.items) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            card.setClipChildren(false);
            // Every cover is 2:3, including the compact shelf above the classic app grid.
            // Subtract caption and focus insets before deriving width, so the art itself keeps the ratio.
            int posterHeight = Math.max(1, cardHeight - dp(23 + 12));
            int width = Math.round(posterHeight * 2f / 3f) + dp(12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, cardHeight);
            lp.rightMargin = dp(16);
            row.addView(card, lp);
            FrameLayout cover = new FrameLayout(activity);
            cover.setClipChildren(false);
            card.addView(cover, new LinearLayout.LayoutParams(-1, posterHeight + dp(12)));
            FrameLayout art = new FrameLayout(activity);
            // Same neutral fill and proportional corners as item_app / AppAdapter.
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(0xff1c1c1e);
            fill.setCornerRadius(AppAdapter.cornerRadius(width - dp(12), density));
            art.setBackground(fill);
            art.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            art.setClipToOutline(true);
            FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(-1, -1);
            artLp.setMargins(dp(6), dp(6), dp(6), dp(6));
            cover.addView(art, artLp);
            TextView fallback = text(14);
            fallback.setText(item.title);
            fallback.setMaxLines(compact ? 3 : 4);
            fallback.setEllipsize(android.text.TextUtils.TruncateAt.END);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(6), dp(4), dp(6), dp(4));
            art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            GlassView focus = new GlassView(activity, null);
            focus.setStyle(config.glassStyle);
            focus.setRadius(AppAdapter.cornerRadius(width - dp(12), density));
            focus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            focus.setVisibility(View.INVISIBLE);
            cover.addView(focus, new FrameLayout.LayoutParams(-1, -1));
            TextView caption = text(12);
            caption.setTypeface(UiFont.get(activity, R.string.font_medium));
            caption.setLetterSpacing(0.02f);
            caption.setAlpha(0.6f);
            caption.setGravity(Gravity.CENTER);
            caption.setText(item.title);
            caption.setMaxLines(1);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            caption.setPadding(dp(2), dp(5), dp(2), 0);
            card.addView(caption, new LinearLayout.LayoutParams(-1, dp(23)));
            if (item.duration > 0 && item.position > 0) {
                ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(100);
                progress.setProgress(item.progress());
                progress.setProgressTintList(ColorStateList.valueOf(0xccffffff));
                progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x26ffffff));
                art.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
            }
            card.setContentDescription(sectionTitle(section) + ": " + item.title + " " + item.episode);
            card.setOnFocusChangeListener((v, focused) -> {
                focus.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                caption.setAlpha(focused ? 1f : 0.6f);
                card.animate().cancel();
                float scale = focused && config.animations && !config.reducedMotion ? 1.05f : 1f;
                if (config.animations && !config.reducedMotion)
                    card.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
                else { card.setScaleX(scale); card.setScaleY(scale); }
                if (focused) {
                    selectedCards.put(sectionIndex, row.indexOfChild(card));
                    describe(item);
                    // Include the full glow in the viewport, including the first and last poster.
                    scroll.post(() -> {
                        if (!card.isFocused() || card.getParent() != row) return;
                        int left = Math.max(0, card.getLeft() - dp(8));
                        int right = card.getRight() + dp(8);
                        int target = scroll.getScrollX();
                        if (left < target + scroll.getPaddingLeft()) target = left - scroll.getPaddingLeft();
                        else if (right > target + scroll.getWidth() - scroll.getPaddingRight())
                            target = right - scroll.getWidth() + scroll.getPaddingRight();
                        if (config.animations && !config.reducedMotion) scroll.smoothScrollTo(Math.max(0, target), 0);
                        else scroll.scrollTo(Math.max(0, target), 0);
                    });
                }
            });
            card.setOnClickListener(v -> open(owner, item.intentUri));
            card.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                // A held vertical key must not skip multiple programme groups or escape to the header.
                if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
                        && event.getRepeatCount() > 0) return true;
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
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int target = row.indexOfChild(card) + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (target >= 0 && target < row.getChildCount()) {
                        row.getChildAt(target).requestFocus();
                        card.playSoundEffect(code == KeyEvent.KEYCODE_DPAD_LEFT
                                ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
                    }
                    return true;
                }
                return false;
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
        clearBackdrop();
        // App browsing keeps the existing wallpaper/glow. Film art appears only inside the film row.
        if (!panel.hasFocus()) return;
        String picture = item.backdropUri.isEmpty() ? item.posterUri : item.backdropUri;
        images.load(picture, true, bitmap -> {
            if (described != item || pkg == null || !panel.hasFocus()) return;
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

    private int tintBackground(int alpha) { return (alpha << 24) | (config.backgroundColor & 0x00ffffff); }

    private TextView text(int size) {
        TextView view = new TextView(activity);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setTypeface(UiFont.get(activity, R.string.font_regular));
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }
}

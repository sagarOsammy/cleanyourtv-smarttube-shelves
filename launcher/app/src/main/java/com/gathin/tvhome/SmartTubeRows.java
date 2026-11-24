package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Three SmartTube rows in an independently scrolling panel above the app dock. */
final class SmartTubeRows {
    private final Activity activity;
    private final FrameLayout root;
    private final LinearLayout panel;
    private final ScrollView viewport;
    private final ShelfImages images;
    private final SavedVideos saved;
    private final Runnable toDock, toTop;
    private final Consumer<PersonalShelfContent.Item> open;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable loadVisiblePictures = this::loadVisiblePictures;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<Row> rows = new ArrayList<>();
    private final float density;
    private final Config config;
    private List<PersonalShelfContent.Item> publishedContinue = Collections.emptyList();
    private boolean dirty = true, loading, destroyed, active;
    private int generation;
    private int cardWidth, cardHeight;
    private float focusScale = 1f;
    private Row focusedRow;
    private ViewTreeObserver.OnPreDrawListener pendingReveal;
    private static final int SECTION_TOP_DP = 122;
    private static final int ROW_GAP_DP = 16;
    private AlertDialog menu;

    private static final class VideoCard extends FrameLayout {
        final ImageView picture;
        View progress;

        VideoCard(Activity activity) {
            super(activity);
            picture = new ImageView(activity);
        }
    }

    private final class Row {
        final int index;
        final String label;
        final LinearLayout container, cards;
        final HorizontalScrollView scroll;
        final FrameLayout strip;
        final SmartTubeFocusController glow;
        final TextView heading, title, creator, runtime;
        final LinearLayout details;
        List<PersonalShelfContent.Item> data = Collections.emptyList();
        PersonalShelfContent.Status status;
        int focused;
        ObjectAnimator horizontalMotion;
        View revealAfterLayout;

        Row(int index, String label) {
            this.index = index;
            this.label = label;
            container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            heading = line(16, 0xFFE8EBF0);
            heading.setTypeface(UiFont.get(activity, R.string.font_medium));
            heading.setText(label);
            container.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            scroll = new HorizontalScrollView(activity) {
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                    return 0; // D-pad moves explicitly reveal cards, leaving other rows in place.
                }
            };
            scroll.setHorizontalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setFocusable(false);
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> schedulePictures());
            strip = new FrameLayout(activity);
            strip.setClipChildren(false);
            cards = new LinearLayout(activity);
            cards.setOrientation(LinearLayout.HORIZONTAL);
            cards.setClipChildren(false);
            cards.setClipToPadding(false);
            cards.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) ->
                    schedulePictures());
            strip.addView(cards, new FrameLayout.LayoutParams(-2, -2));
            scroll.addView(strip, new FrameLayout.LayoutParams(-2, -2));
            container.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            glow = new SmartTubeFocusController(strip, density, config.glassStyle, config.animations,
                    config.reducedMotion, config.focusOvershoot, config.advancedEffects);
            details = new LinearLayout(activity);
            details.setOrientation(LinearLayout.VERTICAL);
            title = line(16, 0xFFFFFFFF);
            title.setTypeface(heading.getTypeface());
            title.setSingleLine(false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            creator = line(12, 0xFFDDCED0);
            runtime = line(12, 0xFFDDCED0);
            details.addView(title, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams creatorLayout = new LinearLayout.LayoutParams(-1, -2);
            creatorLayout.topMargin = dp(8);
            details.addView(creator, creatorLayout);
            LinearLayout.LayoutParams runtimeLayout = new LinearLayout.LayoutParams(-1, -2);
            runtimeLayout.topMargin = dp(5);
            details.addView(runtime, runtimeLayout);
            details.setMinimumHeight(2 * title.getLineHeight() + creator.getLineHeight()
                    + runtime.getLineHeight() + dp(13));
            details.setVisibility(View.GONE);
            container.addView(details, new LinearLayout.LayoutParams(-1, -2));
        }

        void focus(int wanted, boolean revealCard) {
            View card = cards.getChildAt(Math.max(0, Math.min(wanted, cards.getChildCount() - 1)));
            if (card == null || !card.requestFocus() || !revealCard) return;
            if (card.isLayoutRequested() || scroll.getWidth() == 0) revealAfterLayout = card;
            else reveal(card);
        }

        void reveal(View card) {
            stopScroll();
            int margin = horizontalClearance();
            int x = scroll.getScrollX();
            int pitch = cardWidth + margin + dp(4);
            int target = anchoredScrollX(card.getLeft(), card.getRight(), margin, pitch,
                    scroll.getWidth(), strip.getWidth(), cards.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            if (target == x) return;
            if (!config.animations || config.reducedMotion) scroll.scrollTo(target, 0);
            else {
                horizontalMotion = ObjectAnimator.ofInt(scroll, "scrollX", x, target);
                horizontalMotion.setDuration(180);
                horizontalMotion.setInterpolator(new DecelerateInterpolator());
                horizontalMotion.start();
            }
        }

        void stopScroll() {
            if (horizontalMotion == null) return;
            horizontalMotion.cancel();
            horizontalMotion = null;
        }
    }

    SmartTubeRows(Activity activity, FrameLayout root, ShelfImages images, Config config,
            Runnable toDock, Runnable toTop, Consumer<PersonalShelfContent.Item> open) {
        this.activity = activity; this.root = root; this.images = images;
        this.toDock = toDock; this.toTop = toTop; this.open = open;
        this.config = config;
        density = activity.getResources().getDisplayMetrics().density;
        saved = new SavedVideos(activity);
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        viewport = new ScrollView(activity) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0; // Reveal the full active row after its detail lines have been measured.
            }
        };
        viewport.setFocusable(false);
        viewport.setVerticalScrollBarEnabled(false);
        viewport.setOverScrollMode(View.OVER_SCROLL_NEVER);
        viewport.setPadding(dp(14), dp(14), dp(14), dp(14));
        viewport.setClipToPadding(true);
        viewport.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            updateHeadingVisibility();
            schedulePictures();
        });
        viewport.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) ->
                schedulePictures());
        panel.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            updateHeadingVisibility();
            schedulePictures();
        });
        viewport.setClipToOutline(true);
        viewport.addView(panel, new FrameLayout.LayoutParams(-1, -2));
        GradientDrawable band = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xE652171B, 0xEB2C141A, 0xF0141418});
        band.setCornerRadius(dp(26));
        band.setStroke(dp(1), 0x55FF625B);
        viewport.setBackground(band);
        viewport.setVisibility(View.GONE);
        root.addView(viewport, new FrameLayout.LayoutParams(-1, 0));
        int[] labels = {R.string.smarttube_continue, R.string.smarttube_recommendations,
                R.string.smarttube_subscriptions};
        for (int i = 0; i < labels.length; i++) {
            Row row = new Row(i, activity.getString(labels[i])); rows.add(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) lp.topMargin = dp(ROW_GAP_DP);
            panel.addView(row.container, lp);
        }
    }
    private int dp(float value) { return Math.round(value * density); }

    private TextView line(int size, int color) {
        TextView view = new TextView(activity);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int horizontalClearance() {
        return dp(6) + Math.round(cardWidth * (focusScale - 1f) / 2f);
    }

    static int anchoredScrollX(int left, int right, int margin, int pitch,
            int viewportWidth, int stripWidth, boolean rtl) {
        // A narrow viewport can only fit one slot. Keep the selected tile entirely in view.
        int usablePitch = viewportWidth >= pitch + right - left + 2 * margin ? pitch : 0;
        int target = rtl ? right + margin + usablePitch - viewportWidth : left - margin - usablePitch;
        return Math.max(0, Math.min(target, stripWidth - viewportWidth));
    }

    private void updateHeadingVisibility() {
        int top = viewport.getScrollY();
        int bottom = top + viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom();
        for (Row row : rows) {
            int headingTop = row.container.getTop() + row.heading.getTop();
            int headingBottom = row.container.getTop() + row.heading.getBottom();
            row.heading.setAlpha(headingTop >= top && headingBottom <= bottom ? 1f : 0f);
        }
    }

    boolean hasFocus() { return panel.hasFocus(); }
    boolean show(float dockTop, int dockWidth, int dockHeight, float scale) {
        int available = Math.round(dockTop) - dp(SECTION_TOP_DP + 34);
        int width = root.getWidth() - dp(120);
        if (available <= 0 || width <= 0 || dockWidth <= 0 || dockHeight <= 0) {
            hide();
            return false;
        }
        active = true;
        if (dockWidth != cardWidth || dockHeight != cardHeight || scale != focusScale) {
            cardWidth = dockWidth;
            cardHeight = dockHeight;
            focusScale = scale;
            int verticalClearance = dp(6) + Math.round(cardHeight * (scale - 1f) / 2f);
            for (Row row : rows) {
                int horizontal = horizontalClearance();
                row.cards.setPadding(horizontal, verticalClearance, horizontal, verticalClearance);
                row.heading.setPadding(horizontal, 0, horizontal, 0);
                row.details.setPadding(horizontal, 0, horizontal, 0);
                if (row.cards.getChildCount() == 0) bind(row);
                else resizeCards(row);
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) viewport.getLayoutParams();
        if (lp.height != available || lp.width != width) {
            lp.height = available;
            lp.width = width;
            lp.leftMargin = dp(60);
            lp.topMargin = dp(SECTION_TOP_DP);
            viewport.setLayoutParams(lp);
        }
        if (viewport.getVisibility() != View.VISIBLE) {
            viewport.setVisibility(View.VISIBLE);
            schedulePictures();
        }
        viewport.bringToFront();
        refresh();
        return true;
    }
    void hide() {
        active = false;
        viewport.setVisibility(View.GONE);
        clearActiveRow();
    }
    void focusBottom() { focusEdge(rows.get(2), toDock); }
    void focusTop() { focusEdge(rows.get(0), toTop); }
    private void focusEdge(Row row, Runnable fallback) {
        if (viewport.getVisibility() != View.VISIBLE || row.cards.getChildCount() == 0) fallback.run();
        else row.focus(row.focused, true);
    }
    void invalidate() { dirty = true; }
    void refresh() {
        if (!dirty || loading || destroyed || !active || viewport.getVisibility() != View.VISIBLE || hasFocus()) return;
        dirty = false; loading = true;
        int request = ++generation;
        loader.execute(() -> {
            boolean canReadListings = activity.checkSelfPermission(MainActivity.READ_TV_LISTINGS)
                    == PackageManager.PERMISSION_GRANTED;
            PersonalShelfContent.Feeds feeds = PersonalShelfContent.load(activity.getContentResolver(), canReadListings);
            main.post(() -> {
                if (destroyed || request != generation) return;
                loading = false;
                if (!active) { dirty = true; return; }
                if (hasFocus()) { dirty = true; return; } // Never move cards underneath the remote.
                publishedContinue = feeds.next.retainOnFailure(publishedContinue);
                updateRow(rows.get(0), saved.merged(publishedContinue, 24), feeds.next.status);
                updateFeed(rows.get(1), feeds.recommendations);
                updateFeed(rows.get(2), feeds.subscriptions);
                if (dirty) refresh();
            });
        });
    }

    private void updateFeed(Row row, PersonalShelfContent.Feed feed) {
        updateRow(row, feed.retainOnFailure(row.data), feed.status);
    }

    private void updateRow(Row row, List<PersonalShelfContent.Item> updated, PersonalShelfContent.Status status) {
        boolean sameCards = PersonalShelfContent.sameCards(row.data, updated);
        boolean emptyMessageChanged = updated.isEmpty() && row.status != status;
        row.data = updated;
        row.status = status;
        String heading = row.label;
        if (!updated.isEmpty() && status == PersonalShelfContent.Status.UNAVAILABLE) {
            heading = activity.getString(R.string.smarttube_heading_unavailable, row.label);
        } else if (!updated.isEmpty() && status == PersonalShelfContent.Status.PERMISSION_REQUIRED) {
            heading = activity.getString(R.string.smarttube_heading_permission, row.label);
        }
        row.heading.setText(heading);
        if (!sameCards || emptyMessageChanged || row.cards.getChildCount() == 0) bind(row);
    }

    private int emptyMessage(Row row) {
        if (row.status == null) return R.string.smarttube_loading;
        if (row.status == PersonalShelfContent.Status.PERMISSION_REQUIRED) return R.string.smarttube_permission;
        if (row.status == PersonalShelfContent.Status.UNAVAILABLE) return R.string.smarttube_unavailable;
        return row.index == 0 ? R.string.smarttube_continue_empty : R.string.smarttube_feed_empty;
    }
    private void bind(Row row) {
        View previous = row.cards.getChildAt(row.focused);
        Object selection = previous == null ? null : previous.getTag();
        row.stopScroll();
        row.revealAfterLayout = null;
        row.glow.clear();
        row.cards.removeAllViews();
        if (row.data.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(emptyMessage(row));
            empty.setTextSize(12); empty.setTextColor(0xFFBBBBBB); empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setFocusable(true);
            row.cards.addView(empty, new LinearLayout.LayoutParams(-2, cardHeight));
            empty.setOnKeyListener((v, key, event) -> navigate(row, 0, key, event));
            empty.setOnFocusChangeListener((v, isFocused) -> {
                if (isFocused) activate(row, empty, null, 0);
                else deactivateWhenLeaving(row);
            });
            row.focused = 0;
            return;
        }
        for (int i = 0; i < row.data.size(); i++) {
            View card = card(row, row.data.get(i), i);
            row.cards.addView(card);
            if (card.getTag().equals(selection)) row.focused = i;
        }
        resizeCards(row);
        row.focused = Math.min(row.focused, row.cards.getChildCount() - 1);
        schedulePictures();
    }

    private void schedulePictures() {
        main.removeCallbacks(loadVisiblePictures);
        main.post(loadVisiblePictures);
    }

    private void loadVisiblePictures() {
        if (!active || destroyed || !viewport.isShown()) return;
        Rect visible = new Rect();
        for (Row row : rows) {
            if (!row.scroll.getGlobalVisibleRect(visible)) continue;
            for (int i = 0; i < row.data.size(); i++) {
                View card = row.cards.getChildAt(i);
                if (!(card instanceof VideoCard) || !card.getGlobalVisibleRect(visible)) continue;
                ImageView picture = ((VideoCard) card).picture;
                String uri = row.data.get(i).image;
                if (uri != null && picture.getTag() == null) images.load(uri, picture);
            }
        }
    }

    private void resizeCards(Row row) {
        int count = row.cards.getChildCount();
        for (int i = 0; i < count; i++) {
            View card = row.cards.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.height = cardHeight;
            if (!row.data.isEmpty()) {
                lp.width = cardWidth;
                lp.setMarginEnd(i == count - 1 ? 0 : horizontalClearance() + dp(4));
                ((GradientDrawable) card.getBackground()).setCornerRadius(AppAdapter.cornerRadius(cardWidth, density));
                VideoCard thumbnail = (VideoCard) card;
                PersonalShelfContent.Item item = row.data.get(i);
                if (thumbnail.progress != null) {
                    thumbnail.progress.getLayoutParams().width = cardWidth * item.progress / 100;
                }
            }
            card.setLayoutParams(lp);
        }
    }

    private View card(Row row, PersonalShelfContent.Item item, int index) {
        VideoCard card = new VideoCard(activity);
        card.setLayoutParams(new LinearLayout.LayoutParams(cardWidth, cardHeight));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF252932);
        card.setBackground(background); card.setClipToOutline(true); card.setFocusable(true);
        card.setTag(SavedVideoRules.key(item));
        card.setContentDescription(item.title);
        TextView fallback = new TextView(activity);
        fallback.setText(item.title); fallback.setTextColor(0xFFFFFFFF); fallback.setTextSize(10);
        fallback.setMaxLines(3); fallback.setGravity(Gravity.CENTER); fallback.setPadding(dp(4), 0, dp(4), 0);
        card.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        card.picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(card.picture, new FrameLayout.LayoutParams(-1, -1));
        if (row.index == 0 && item.progress > 0) {
            View track = new View(activity); track.setBackgroundColor(0x99000000);
            card.addView(track, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
            card.progress = new View(activity);
            card.progress.setBackgroundColor(0xFFFFFFFF);
            card.addView(card.progress,
                    new FrameLayout.LayoutParams(cardWidth * item.progress / 100, dp(3), Gravity.BOTTOM));
        }
        card.setOnClickListener(v -> open.accept(item));
        card.setOnLongClickListener(v -> { showMenu(row, item); return true; });
        // Handle physical holds and ADB FLAG_LONG_PRESS without also playing on release.
        final boolean[] held = {false}, pressed = {false};
        Runnable hold = () -> { if (pressed[0] && card.hasFocus() && activity.hasWindowFocus() && !destroyed) {
            held[0] = true; card.performLongClick(); } };
        card.setOnFocusChangeListener((v, isFocused) -> {
            scaleCard(card, isFocused);
            if (isFocused) activate(row, card, item, index);
            else {
                main.removeCallbacks(hold);
                pressed[0] = false;
                deactivateWhenLeaving(row);
            }
        });
        card.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        held[0] = false; pressed[0] = true;
                        main.postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
                    } else if (!held[0] && pressed[0] && event.isLongPress()) {
                        held[0] = true; main.removeCallbacks(hold); card.performLongClick();
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    main.removeCallbacks(hold);
                    boolean click = pressed[0] && !held[0] && !event.isCanceled(); pressed[0] = false;
                    if (click) card.performClick();
                }
                return true;
            }
            return navigate(row, index, key, event);
        });
        return card;
    }

    private void scaleCard(View card, boolean focused) {
        float scale = focused ? focusScale : 1f;
        card.animate().cancel();
        if (!config.animations) {
            card.setScaleX(scale);
            card.setScaleY(scale);
        } else {
            long duration = config.reducedMotion ? (focused ? Motion.REDUCED_MS : 60)
                    : config.advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180);
            Interpolator easing;
            if (config.reducedMotion) easing = new DecelerateInterpolator();
            else if (focused && !config.focusOvershoot) easing = new DecelerateInterpolator(1.5f);
            else easing = new OvershootInterpolator(focused ? (config.advancedEffects ? 0.7f : 1.6f) : 0f);
            card.animate().scaleX(scale).scaleY(scale).setDuration(duration).setInterpolator(easing).start();
        }
    }

    private void activate(Row row, View card, PersonalShelfContent.Item item, int index) {
        if (focusedRow != row) {
            clearActiveRow();
            focusedRow = row;
        }
        row.focused = index;
        row.heading.setTextColor(0xFFFFFFFF);
        row.details.setVisibility(item == null ? View.GONE : View.VISIBLE);
        float scale = item == null ? 1f : focusScale;
        if (item != null) {
            row.title.setText(item.title);
            setOptionalLine(row.creator, item.creatorAndUpload());
            setOptionalLine(row.runtime, item.durationAndViews());
        }
        if (card.getWidth() > 0 && !card.isLayoutRequested()) row.glow.show(card, scale);
        cancelReveal();
        pendingReveal = () -> {
            cancelReveal();
            if (!card.isFocused() || focusedRow != row) return true;
            row.glow.show(card, scale);
            if (row.revealAfterLayout == card) {
                row.revealAfterLayout = null;
                row.reveal(card);
            }
            int top = row.container.getTop();
            int bottom = row.container.getBottom();
            int y = viewport.getScrollY();
            int height = viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom();
            int target = top < y || bottom - top > height ? top : Math.max(y, bottom - height);
            if (target != y) viewport.scrollTo(0, target);
            updateHeadingVisibility();
            return true;
        };
        viewport.getViewTreeObserver().addOnPreDrawListener(pendingReveal);
    }

    private static void setOptionalLine(TextView line, String value) {
        line.setText(value);
        line.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
    }

    private void cancelReveal() {
        if (pendingReveal == null) return;
        ViewTreeObserver tree = viewport.getViewTreeObserver();
        if (tree.isAlive()) tree.removeOnPreDrawListener(pendingReveal);
        pendingReveal = null;
    }

    private void deactivateWhenLeaving(Row row) {
        main.post(() -> {
            if (focusedRow == row && !row.cards.hasFocus()) {
                clearActiveRow();
                refresh();
            }
        });
    }

    private void clearActiveRow() {
        cancelReveal();
        if (focusedRow == null) return;
        focusedRow.glow.clear();
        focusedRow.heading.setTextColor(0xFFE8EBF0);
        focusedRow.stopScroll();
        focusedRow.revealAfterLayout = null;
        focusedRow.details.setVisibility(View.GONE);
        focusedRow = null;
    }

    private boolean navigate(Row row, int index, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = row.index + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next < 0) toTop.run();
            else if (next >= rows.size()) toDock.run();
            else {
                Row destination = rows.get(next);
                View source = row.cards.getChildAt(index);
                float sourceX = SmartTubeFocusController.layoutCenterXOnScreen(row.cards, source);
                int nearest = SmartTubeFocusController.nearestVisible(destination.cards, destination.scroll, sourceX);
                destination.focus(nearest < 0 ? destination.focused : nearest, false);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) { toDock.run(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean towardEnd = (key == KeyEvent.KEYCODE_DPAD_RIGHT)
                    != (row.cards.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            int next = index + (towardEnd ? 1 : -1);
            if (next >= 0 && next < row.cards.getChildCount()) row.focus(next, true);
            return true;
        }
        return false;
    }
    private void showMenu(Row row, PersonalShelfContent.Item item) {
        if (menu != null && menu.isShowing()) return;
        boolean remove = row.index == 0 || saved.contains(item, publishedContinue);
        menu = new AlertDialog.Builder(activity).setTitle(item.title)
                .setItems(new CharSequence[]{activity.getText(remove ? R.string.smarttube_remove : R.string.smarttube_add)},
                        (dialog, which) -> {
                            if (remove) saved.remove(item); else saved.add(item);
                            Row watching = rows.get(0);
                            int focus = watching.focused;
                            watching.data = saved.merged(publishedContinue, 24);
                            bind(watching);
                            if (row.index == 0) watching.focus(focus, true);
                            Toast.makeText(activity, remove ? R.string.smarttube_removed : R.string.smarttube_added,
                                    Toast.LENGTH_SHORT).show();
                        }).setNegativeButton(android.R.string.cancel, null).create();
        menu.show();
    }
    void resume() {
        active = viewport.getVisibility() == View.VISIBLE;
        for (Row row : rows) {
            for (int i = 0; i < row.cards.getChildCount(); i++) {
                View card = row.cards.getChildAt(i);
                if (!(card instanceof VideoCard)) continue;
                ImageView picture = ((VideoCard) card).picture;
                if (picture.getDrawable() == null) picture.setTag(null);
            }
        }
        schedulePictures();
        invalidate();
        refresh();
    }
    void pause() { active = false; if (menu != null) menu.dismiss(); }
    void trim() {
        hide(); for (Row row : rows) row.cards.removeAllViews(); images.trim(); dirty = true;
    }
    void destroy() {
        destroyed = true; generation++; loader.shutdownNow(); main.removeCallbacksAndMessages(null);
        clearActiveRow();
        if (menu != null) menu.dismiss();
    }
}

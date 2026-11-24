package com.gathin.tvhome;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Focus glass and horizontal target selection for one SmartTube row. */
final class SmartTubeFocusController {
    private static final float GLASS_INSET_DP = 6f;

    private final FrameLayout strip;
    private final float density;
    private final boolean animations, reducedMotion;
    private final GlassView glass;
    private final SpringAnimation springX, springY;
    private boolean shown, positioned;
    private int generation;
    private int targetLeft, targetTop, targetWidth, targetHeight;

    SmartTubeFocusController(FrameLayout strip, float density, String glassStyle,
            boolean animations, boolean reducedMotion, boolean overshoot, boolean advancedEffects) {
        this.strip = strip;
        this.density = density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;

        // ViewGroupOverlay draws above the scrolling row without becoming a measured child of its wrap-content
        // FrameLayout. The parent HorizontalScrollView still clips the glass at the viewport edge.
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        glass = new GlassView(strip.getContext(), null);
        glass.setStyle(glassStyle);
        glass.setFocusable(false);
        glass.setFocusableInTouchMode(false);
        glass.setClickable(false);
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glass.setVisibility(View.GONE);
        strip.getOverlay().add(glass);

        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation spring : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(spring);
            } else if (!overshoot) {
                spring.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
            } else {
                spring.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }
    }

    /** Moves the row's floating glass to the unscaled layout bounds of the focused card. */
    void show(View card, float scale) {
        if (card == null || card.getWidth() <= 0 || card.getHeight() <= 0 || strip.getWidth() <= 0) return;
        ViewGroup parent = card.getParent() instanceof ViewGroup ? (ViewGroup) card.getParent() : null;
        if (!(parent instanceof LinearLayout) || parent.getParent() != strip) return;
        LinearLayout cards = (LinearLayout) parent;

        float safeScale = Math.max(1f, scale);
        int pad = dp(GLASS_INSET_DP);
        int width = Math.max(1, Math.round(card.getWidth() * safeScale + 2f * pad));
        int height = Math.max(1, Math.round(card.getHeight() * safeScale + 2f * pad));
        int left = cards.getLeft() + card.getLeft() + Math.round((card.getWidth() - width) / 2f);
        int top = cards.getTop() + card.getTop() + Math.round((card.getHeight() - height) / 2f);

        if (shown && targetLeft == left && targetTop == top
                && targetWidth == width && targetHeight == height) return;

        // Keep the initial fade-in running while focus moves within the row. Cancel only when reopening after clear,
        // where an old fade-out must not keep hiding the new target.
        if (!shown) glass.animate().cancel();
        springX.cancel();
        springY.cancel();
        glass.setVisibility(View.VISIBLE);
        layoutGlass(width, height);
        glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * safeScale);

        generation++;
        if (!animations) {
            glass.setTranslationX(left);
            glass.setTranslationY(top);
        } else if (!positioned) {
            // The first target appears in place. Later moves retain the prior target as the spring's start.
            glass.setTranslationX(left);
            glass.setTranslationY(top);
        } else {
            springX.animateToFinalPosition(left);
            springY.animateToFinalPosition(top);
        }
        positioned = true;
        targetLeft = left;
        targetTop = top;
        targetWidth = width;
        targetHeight = height;

        if (!shown) {
            shown = true;
            if (animations && !reducedMotion) {
                glass.setAlpha(0f);
                glass.animate().alpha(1f).setDuration(180).start();
            } else {
                glass.setAlpha(1f);
            }
        }
    }

    /** Cancels motion and hides this row's glass. A following show can still spring from its last target. */
    void clear() {
        if (!shown && glass.getVisibility() != View.VISIBLE) return;
        shown = false;
        positioned = false;
        int request = ++generation;
        springX.cancel();
        springY.cancel();
        glass.animate().cancel();
        if (animations && !reducedMotion) {
            glass.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                if (!shown && generation == request) glass.setVisibility(View.GONE);
            }).start();
        } else {
            glass.setAlpha(0f);
            glass.setVisibility(View.GONE);
        }
    }

    /** Screen X of a card's unscaled layout center, suitable for {@link #nearestVisible}. */
    static float layoutCenterXOnScreen(LinearLayout cards, View card) {
        int[] location = new int[2];
        cards.getLocationOnScreen(location);
        return location[0] + card.getLeft() + card.getWidth() / 2f;
    }

    /**
     * Finds the horizontally visible, focusable child whose visible center is nearest screenX.
     * Vertical visibility is intentionally ignored so a row below the panel fold remains a valid destination.
     */
    static int nearestVisible(LinearLayout cards, HorizontalScrollView viewport, float screenX) {
        if (cards == null || viewport == null || cards.getChildCount() == 0
                || Float.isNaN(screenX) || Float.isInfinite(screenX)) return -1;

        int[] cardsLocation = new int[2];
        int[] viewportLocation = new int[2];
        cards.getLocationOnScreen(cardsLocation);
        viewport.getLocationOnScreen(viewportLocation);
        float viewportLeft = viewportLocation[0] + viewport.getPaddingLeft();
        float viewportRight = viewportLocation[0] + viewport.getWidth() - viewport.getPaddingRight();
        if (viewportRight <= viewportLeft) return -1;

        int count = cards.getChildCount();
        float[] lefts = new float[count];
        float[] rights = new float[count];
        boolean[] eligible = new boolean[count];
        for (int i = 0; i < count; i++) {
            View card = cards.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE || !card.isFocusable()
                    || card.getWidth() <= 0 || card.getHeight() <= 0) continue;
            // Use layout bounds, not getLocationOnScreen(card): focus scaling must not change target ranking.
            lefts[i] = cardsLocation[0] + card.getLeft();
            rights[i] = lefts[i] + card.getWidth();
            eligible[i] = true;
        }
        return nearestIndex(screenX, viewportLeft, viewportRight, lefts, rights, eligible);
    }

    /** Pure geometry core, kept package-visible so the directional policy can be tested on the JVM. */
    static int nearestIndex(float screenX, float viewportLeft, float viewportRight,
            float[] lefts, float[] rights, boolean[] eligible) {
        if (lefts == null || rights == null || eligible == null || lefts.length == 0
                || lefts.length != rights.length || lefts.length != eligible.length
                || Float.isNaN(screenX) || Float.isInfinite(screenX)
                || Float.isNaN(viewportLeft) || Float.isNaN(viewportRight)
                || viewportRight <= viewportLeft) return -1;

        int best = -1;
        boolean haveFullyVisible = false;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < lefts.length; i++) {
            if (!eligible[i] || Float.isNaN(lefts[i]) || Float.isNaN(rights[i]) || rights[i] <= lefts[i]) continue;
            float visibleLeft = Math.max(lefts[i], viewportLeft);
            float visibleRight = Math.min(rights[i], viewportRight);
            if (visibleRight <= visibleLeft) continue;
            boolean fullyVisible = lefts[i] >= viewportLeft && rights[i] <= viewportRight;
            if (fullyVisible && !haveFullyVisible) {
                best = -1;
                bestDistance = Float.MAX_VALUE;
                haveFullyVisible = true;
            } else if (!fullyVisible && haveFullyVisible) {
                continue;
            }
            float center = fullyVisible ? (lefts[i] + rights[i]) / 2f : (visibleLeft + visibleRight) / 2f;
            float distance = Math.abs(center - screenX);
            // Strict comparison preserves the lower child index for exact ties.
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void layoutGlass(int width, int height) {
        if (glass.getWidth() == width && glass.getHeight() == height) return;
        glass.setLayoutParams(new ViewGroup.LayoutParams(width, height));
        glass.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        glass.layout(0, 0, width, height);
    }

    private int dp(float value) { return Math.round(value * density); }
}

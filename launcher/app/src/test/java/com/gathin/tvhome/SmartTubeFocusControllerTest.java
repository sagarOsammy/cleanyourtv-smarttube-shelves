package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SmartTubeFocusControllerTest {
    @Test public void anchorsHorizontalFocusAtTheSecondSlotAndClampsTheEnds() {
        assertEquals(0, SmartTubeRows.anchoredScrollX(10, 110, 10, 120, 360, 960, false));
        assertEquals(0, SmartTubeRows.anchoredScrollX(130, 230, 10, 120, 360, 960, false));
        assertEquals(480, SmartTubeRows.anchoredScrollX(610, 710, 10, 120, 360, 960, false));
        assertEquals(600, SmartTubeRows.anchoredScrollX(850, 950, 10, 120, 360, 960, false));
    }

    @Test public void mirrorsTheAnchorAndUsesTheFirstSlotOnNarrowScreens() {
        assertEquals(120, SmartTubeRows.anchoredScrollX(250, 350, 10, 120, 360, 960, true));
        assertEquals(240, SmartTubeRows.anchoredScrollX(250, 350, 10, 120, 140, 960, false));
    }

    @Test public void choosesNearestVisibleCenterInsteadOfMatchingTheSourceIndex() {
        assertEquals(1, SmartTubeFocusController.nearestIndex(178f, 0f, 320f,
                new float[]{0f, 140f, 230f}, new float[]{60f, 210f, 290f},
                new boolean[]{true, true, true}));
    }

    @Test public void clipsCandidatesToTheHorizontalViewportAndSkipsOffscreenCards() {
        assertEquals(2, SmartTubeFocusController.nearestIndex(147f, 100f, 300f,
                new float[]{0f, 82f, 128f, 310f}, new float[]{90f, 126f, 174f, 350f},
                new boolean[]{true, true, true, true}));
    }

    @Test public void ignoresNonFocusableOrHiddenChildrenAndKeepsLowerIndexOnTies() {
        assertEquals(1, SmartTubeFocusController.nearestIndex(120f, 0f, 200f,
                new float[]{20f, 70f, 130f}, new float[]{60f, 110f, 170f},
                new boolean[]{false, true, true}));
    }

    @Test public void prefersFullyVisibleCardsBeforeComparingDistance() {
        assertEquals(1, SmartTubeFocusController.nearestIndex(190f, 0f, 200f,
                new float[]{190f, 80f}, new float[]{240f, 120f},
                new boolean[]{true, true}));
    }

    @Test public void returnsMinusOneWhenNothingIsVisibleOrFocusable() {
        assertEquals(-1, SmartTubeFocusController.nearestIndex(100f, 0f, 100f,
                new float[]{120f, 140f}, new float[]{160f, 180f},
                new boolean[]{true, false}));
    }

    @Test public void ignoresVerticalPositionBecauseThePanelMayScrollBelowTheFold() {
        // The policy consumes horizontal bounds only; no vertical coordinate can invalidate a destination.
        assertEquals(0, SmartTubeFocusController.nearestIndex(45f, 0f, 120f,
                new float[]{10f}, new float[]{80f}, new boolean[]{true}));
    }
}

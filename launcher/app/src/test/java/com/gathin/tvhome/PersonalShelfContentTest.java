package com.gathin.tvhome;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalShelfContentTest {
    private final long now = 200L * 24 * 60 * 60 * 1000;
    @Test public void hidesExplicitlyRemovedAndOldEntries() {
        assertFalse(PersonalShelfContent.keepContinue(0, 0, 500, 1000, now, now));
        assertFalse(PersonalShelfContent.keepContinue(1, 0, 500, 1000, 1, now));
        assertTrue(PersonalShelfContent.keepContinue(1, 0, 500, 1000, now, now));
    }
    @Test public void dropsFinishedContinueButKeepsNextEpisodeAndWatchlist() {
        assertFalse(PersonalShelfContent.keepContinue(1, 0, 950, 1000, now, now));
        assertTrue(PersonalShelfContent.keepContinue(1, 0, 949, 1000, now, now));
        assertTrue(PersonalShelfContent.keepContinue(1, 1, 0, 1000, now, now));
        assertTrue(PersonalShelfContent.keepContinue(1, 3, 0, 0, now, now));
    }
    @Test public void missingDurationOrTimestampDoesNotDiscardAValidEntry() {
        assertTrue(PersonalShelfContent.keepContinue(1, 0, 0, 0, 0, now));
        assertEquals(-1, PersonalShelfContent.progress(500, 0));
        assertEquals(50, PersonalShelfContent.progress(500, 1000));
        assertEquals(100, PersonalShelfContent.progress(Long.MAX_VALUE, 1000));
    }
    @Test public void formatsSmartTubeCreatorUploadDurationAndViews() {
        PersonalShelfContent.Item item = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", -1, 1, "Creator • 1.2M views • 3 days ago", 3_723_000);
        assertEquals("Creator · 3 days ago", item.creatorAndUpload());
        assertEquals("1:02:03 · 1.2M views", item.durationAndViews());
    }
    @Test public void skipsSmartTubeBadgeBeforeCreator() {
        PersonalShelfContent.Item item = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", -1, 1, "4K • Creator • 1.2M views • 3 days ago", 65_000);
        assertEquals("Creator · 3 days ago", item.creatorAndUpload());
        assertEquals("1:05 · 1.2M views", item.durationAndViews());
    }
    @Test public void keepsUnrecognizedDescriptionVerbatimAndStillShowsDuration() {
        String raw = "Creator • a localized statistic • recently uploaded";
        PersonalShelfContent.Item item = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", -1, 1, raw, 65_000);
        assertEquals(raw, item.creatorAndUpload());
        assertEquals("1:05", item.durationAndViews());
    }
    @Test public void oldItemConstructorStillWorksWithoutMetadata() {
        PersonalShelfContent.Item item = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", -1, 1);
        assertNull(item.creatorAndUpload());
        assertNull(item.durationAndViews());
    }
    @Test public void onlyTemporaryFailuresRetainPreviouslyLoadedProviderRows() {
        PersonalShelfContent.Item item = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", -1, 1);
        List<PersonalShelfContent.Item> previous = Collections.singletonList(item);
        List<PersonalShelfContent.Item> empty = Collections.emptyList();
        assertSame(previous, new PersonalShelfContent.Feed(PersonalShelfContent.Status.UNAVAILABLE, empty)
                .retainOnFailure(previous));
        assertTrue(new PersonalShelfContent.Feed(PersonalShelfContent.Status.READY, empty)
                .retainOnFailure(previous).isEmpty());
        assertTrue(PersonalShelfContent.load(null, false).next.retainOnFailure(previous).isEmpty());
    }

    @Test public void refreshedCardsIgnoreTimestampsButRebindWhenProgressChanges() {
        PersonalShelfContent.Item before = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", 20, 1);
        PersonalShelfContent.Item timestampOnly = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", 20, 2);
        PersonalShelfContent.Item resumed = new PersonalShelfContent.Item("Video", null, "intent",
                "org.smarttube.stable", 40, 2);
        assertTrue(PersonalShelfContent.sameCards(Collections.singletonList(before),
                Collections.singletonList(timestampOnly)));
        assertFalse(PersonalShelfContent.sameCards(Collections.singletonList(before),
                Collections.singletonList(resumed)));
    }
}

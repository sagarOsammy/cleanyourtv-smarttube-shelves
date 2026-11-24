package com.gathin.tvhome;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SavedVideoRulesTest {
    private PersonalShelfContent.Item video(String uri, int progress, long time) {
        return new PersonalShelfContent.Item("Video", null, uri, "org.smarttube.stable", progress, time);
    }
    @Test public void deduplicatesSavedVideoAndSmartTubeResumeEntry() {
        PersonalShelfContent.Item saved = video("https://www.youtube.com/watch?v=abcdefghijk", -1, 200);
        PersonalShelfContent.Item resumed = video("tvhomescreenchannels://com.google.android.tvhomescreenchannels/playvideo/abcdefghijk", 40, 100);
        List<PersonalShelfContent.Item> result = SavedVideoRules.merge(Arrays.asList(saved), Arrays.asList(resumed), Collections.emptySet(), 24);
        assertEquals(1, result.size()); assertEquals(40, result.get(0).progress);
        assertEquals(resumed.intentUri, result.get(0).intentUri); assertEquals(200, result.get(0).order);
    }
    @Test public void metadataMergeKeepsFreshProviderDetailsAndSavedFallbacks() {
        PersonalShelfContent.Item saved = new PersonalShelfContent.Item("Video", null,
                "https://youtu.be/abcdefghijk", "org.smarttube.stable", -1, 200,
                "Saved creator • 2 days ago", 60_000);
        PersonalShelfContent.Item resumed = new PersonalShelfContent.Item("Video", null,
                "tvhomescreenchannels://com.google.android.tvhomescreenchannels/playvideo/abcdefghijk",
                "org.smarttube.stable", 40, 100, "Fresh creator • 2.4K views • 1 day ago", 120_000);
        List<PersonalShelfContent.Item> fresh = SavedVideoRules.merge(Arrays.asList(saved),
                Arrays.asList(resumed), Collections.emptySet(), 24);
        assertEquals("Fresh creator · 1 day ago", fresh.get(0).creatorAndUpload());
        assertEquals("2:00 · 2.4K views", fresh.get(0).durationAndViews());

        PersonalShelfContent.Item withoutMetadata = new PersonalShelfContent.Item("Video", null,
                resumed.intentUri, resumed.packageName, 40, 100, null, 0);
        List<PersonalShelfContent.Item> fallback = SavedVideoRules.merge(Arrays.asList(saved),
                Arrays.asList(withoutMetadata), Collections.emptySet(), 24);
        assertEquals("Saved creator · 2 days ago", fallback.get(0).creatorAndUpload());
        assertEquals("1:00", fallback.get(0).durationAndViews());
    }
    @Test public void duplicateProviderLinksKeepNewestProgressAndManualSaveDate() {
        PersonalShelfContent.Item saved = video("https://youtu.be/abcdefghijk", -1, 300);
        PersonalShelfContent.Item newest = video("https://www.youtube.com/watch?v=abcdefghijk", 60, 200);
        PersonalShelfContent.Item older = video(
                "tvhomescreenchannels://com.google.android.tvhomescreenchannels/playvideo/abcdefghijk", 20, 100);
        List<PersonalShelfContent.Item> result = SavedVideoRules.merge(Arrays.asList(saved),
                Arrays.asList(newest, older), Collections.emptySet(), 24);
        assertEquals(1, result.size());
        assertEquals(60, result.get(0).progress);
        assertEquals(newest.intentUri, result.get(0).intentUri);
        assertEquals(300, result.get(0).order);
    }
    @Test public void removalHidesBothSavedAndAutomaticVersions() {
        PersonalShelfContent.Item item = video("https://youtu.be/abcdefghijk", 10, 100);
        assertTrue(SavedVideoRules.merge(Arrays.asList(item), Arrays.asList(item),
                Collections.singleton(SavedVideoRules.key(item)), 24).isEmpty());
        assertEquals(1, SavedVideoRules.merge(Arrays.asList(item), Collections.emptyList(), Collections.emptySet(), 24).size());
    }
    @Test public void neverImportsOtherAppsIntoSmartTubeRow() {
        PersonalShelfContent.Item other = new PersonalShelfContent.Item("Other", null, "https://example.com", "com.example.player", 50, 100);
        assertTrue(SavedVideoRules.merge(Arrays.asList(other), Arrays.asList(other), Collections.emptySet(), 24).isEmpty());
    }
    @Test public void newestManualSaveIsFirstAndLimitIsRespected() {
        PersonalShelfContent.Item older = video("https://youtu.be/abcdefghijk", -1, 100);
        PersonalShelfContent.Item newer = video("https://youtu.be/lmnopqrstuv", -1, 200);
        List<PersonalShelfContent.Item> result = SavedVideoRules.merge(Arrays.asList(older, newer), Collections.emptyList(), Collections.emptySet(), 1);
        assertEquals(1, result.size()); assertEquals(newer.intentUri, result.get(0).intentUri);
    }
}

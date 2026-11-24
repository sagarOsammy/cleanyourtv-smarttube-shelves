package com.gathin.tvhome;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure rules shared by automatic SmartTube progress and manually saved videos. */
final class SavedVideoRules {
    private static final Pattern VIDEO = Pattern.compile(
            "(?:[?&](?:v|video_id)=|youtu\\.be/|/playvideo/|/(?:shorts|live|embed)/)([A-Za-z0-9_-]{11})(?=[&#;/?]|$)");
    static String key(PersonalShelfContent.Item item) {
        String uri = item.intentUri;
        try { uri = URLDecoder.decode(uri, "UTF-8"); } catch (Exception ignored) { }
        Matcher match = VIDEO.matcher(uri);
        return item.packageName + ":" + (match.find() ? match.group(1) : item.intentUri);
    }
    static List<PersonalShelfContent.Item> merge(List<PersonalShelfContent.Item> saved,
            List<PersonalShelfContent.Item> published, Set<String> hidden, int max) {
        Map<String, PersonalShelfContent.Item> byVideo = new LinkedHashMap<>();
        for (PersonalShelfContent.Item item : saved) if ("org.smarttube.stable".equals(item.packageName))
            byVideo.put(key(item), item);
        Map<String, PersonalShelfContent.Item> latestPublished = new LinkedHashMap<>();
        for (PersonalShelfContent.Item item : published) {
            if (!"org.smarttube.stable".equals(item.packageName)) continue;
            latestPublished.merge(key(item), item, (previous, candidate) ->
                    candidate.order > previous.order ? candidate : previous);
        }
        for (Map.Entry<String, PersonalShelfContent.Item> entry : latestPublished.entrySet()) {
            String key = entry.getKey();
            PersonalShelfContent.Item item = entry.getValue();
            PersonalShelfContent.Item old = byVideo.get(key);
            // Use the newest SmartTube progress and keep whichever date is more recent for ordering.
            byVideo.put(key, old == null ? item : new PersonalShelfContent.Item(item.title,
                    item.image == null ? old.image : item.image, item.intentUri, item.packageName,
                    item.progress, Math.max(old.order, item.order),
                    PersonalShelfContent.firstNonEmpty(item.description, old.description),
                    item.durationMillis > 0 ? item.durationMillis : old.durationMillis));
        }
        List<PersonalShelfContent.Item> result = new ArrayList<>();
        for (Map.Entry<String, PersonalShelfContent.Item> entry : byVideo.entrySet())
            if (!hidden.contains(entry.getKey())) result.add(entry.getValue());
        result.sort((a, b) -> Long.compare(b.order, a.order));
        return new ArrayList<>(result.subList(0, Math.min(max, result.size())));
    }
}

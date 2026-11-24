package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Private local watch-later choices; no writes to another app's TV rows or account. */
final class SavedVideos {
    private final SharedPreferences preferences;
    SavedVideos(Context context) { preferences = context.getSharedPreferences("smarttube_saved_videos", Context.MODE_PRIVATE); }
    private Set<String> hidden() { return new HashSet<>(preferences.getStringSet("hidden", new HashSet<>())); }
    private List<PersonalShelfContent.Item> saved() {
        List<PersonalShelfContent.Item> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(preferences.getString("videos", "[]"));
            for (int i = 0; i < rows.length() && i < 100; i++) {
                JSONObject row = rows.getJSONObject(i);
                result.add(new PersonalShelfContent.Item(row.getString("title"), row.optString("image", null),
                        row.getString("intent"), "org.smarttube.stable", -1, row.getLong("saved_at"),
                        row.optString("description", null), row.optLong("duration_millis", 0)));
            }
        } catch (Exception ignored) { }
        return result;
    }
    boolean contains(PersonalShelfContent.Item video, List<PersonalShelfContent.Item> published) {
        String wanted = SavedVideoRules.key(video);
        for (PersonalShelfContent.Item item : merged(published, 100))
            if (wanted.equals(SavedVideoRules.key(item))) return true;
        return false;
    }
    List<PersonalShelfContent.Item> merged(List<PersonalShelfContent.Item> published, int max) {
        return SavedVideoRules.merge(saved(), published, hidden(), max);
    }
    void add(PersonalShelfContent.Item video) {
        if (!"org.smarttube.stable".equals(video.packageName)) return;
        String key = SavedVideoRules.key(video);
        List<PersonalShelfContent.Item> rows = saved();
        rows.removeIf(item -> key.equals(SavedVideoRules.key(item)));
        rows.add(0, new PersonalShelfContent.Item(video.title, video.image, video.intentUri,
                video.packageName, -1, System.currentTimeMillis(), video.description, video.durationMillis));
        Set<String> hidden = hidden(); hidden.remove(key);
        write(rows, hidden);
    }
    void remove(PersonalShelfContent.Item video) {
        String key = SavedVideoRules.key(video);
        List<PersonalShelfContent.Item> rows = saved();
        rows.removeIf(item -> key.equals(SavedVideoRules.key(item)));
        Set<String> hidden = hidden(); hidden.add(key);
        write(rows, hidden);
    }
    private void write(List<PersonalShelfContent.Item> videos, Set<String> hidden) {
        JSONArray rows = new JSONArray();
        try {
            for (int i = 0; i < Math.min(100, videos.size()); i++) {
                PersonalShelfContent.Item video = videos.get(i);
                JSONObject row = new JSONObject();
                row.put("title", video.title); row.put("image", video.image);
                row.put("intent", video.intentUri); row.put("saved_at", video.order);
                if (video.description != null) row.put("description", video.description);
                if (video.durationMillis > 0) row.put("duration_millis", video.durationMillis);
                rows.put(row);
            }
            preferences.edit().putString("videos", rows.toString()).putStringSet("hidden", hidden).apply();
        } catch (Exception ignored) { }
    }
}

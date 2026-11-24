package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only shelves from the TV provider. SmartTube owns its login and refresh. */
final class PersonalShelfContent {
    static final String SMARTTUBE = "org.smarttube.stable";
    private static final long MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000;
    static final class Item {
        final String title, image, intentUri, packageName;
        final String description;
        final int progress;
        final long order, durationMillis;
        Item(String title, String image, String intent, String pkg, int progress, long order) {
            this(title, image, intent, pkg, progress, order, null, 0);
        }
        Item(String title, String image, String intent, String pkg, int progress, long order,
                String description, long durationMillis) {
            this.title = title; this.image = image; this.intentUri = intent;
            this.packageName = pkg; this.progress = progress; this.order = order;
            this.description = description; this.durationMillis = durationMillis;
        }
        /** Creator and upload age when SmartTube's subtitle follows its known bullet format. */
        String creatorAndUpload() {
            DescriptionMetadata metadata = parseDescription(description);
            return metadata.parsed ? metadata.creatorAndUpload : metadata.raw;
        }
        /** Formatted duration and view count when SmartTube supplies a recognizable subtitle. */
        String durationAndViews() {
            String duration = formatDuration(durationMillis);
            DescriptionMetadata metadata = parseDescription(description);
            String views = metadata.parsed ? metadata.views : null;
            if (duration == null) return views;
            return views == null ? duration : duration + " · " + views;
        }
    }
    private PersonalShelfContent() { }
    // Explicit projections avoid unsupported default columns on some TV providers.
    private static final String[] BASE = {"title", "episode_title", "poster_art_uri", "thumbnail_uri",
            "intent_uri", "last_playback_position_millis", "duration_millis", "browsable", "package_name",
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION};
    private static String[] columns(String... extra) {
        String[] result = new String[BASE.length + extra.length];
        System.arraycopy(BASE, 0, result, 0, BASE.length);
        System.arraycopy(extra, 0, result, BASE.length, extra.length);
        return result;
    }
    enum Status { READY, PERMISSION_REQUIRED, UNAVAILABLE }

    static final class Feed {
        final Status status;
        final List<Item> items;
        Feed(Status status, List<Item> items) {
            this.status = status;
            this.items = items;
        }

        List<Item> retainOnFailure(List<Item> previous) {
            return status == Status.UNAVAILABLE ? previous : items;
        }
    }

    static final class Feeds {
        final Feed next, recommendations, subscriptions;
        Feeds(Feed next, Feed recommendations, Feed subscriptions) {
            this.next = next;
            this.recommendations = recommendations;
            this.subscriptions = subscriptions;
        }
    }

    static Feeds load(ContentResolver resolver, boolean canReadListings) {
        if (!canReadListings) {
            Feed denied = failed(Status.PERMISSION_REQUIRED);
            return new Feeds(denied, denied, denied);
        }
        Feed next = watchNext(resolver);
        try {
            // No SQL selection: non-privileged callers must filter in code.
            Map<Long, List<Item>> channelItems = new HashMap<>();
            List<Item> recommendations = new ArrayList<>();
            List<Item> subscriptions = new ArrayList<>();
            try (Cursor c = requireCursor(resolver.query(TvContract.Channels.CONTENT_URI,
                    new String[]{"_id", "package_name", "internal_provider_id", "type"}, null, null, null))) {
                while (c.moveToNext()) {
                    if (!SMARTTUBE.equals(c.getString(1)) || !TvContract.Channels.TYPE_PREVIEW.equals(c.getString(3))) continue;
                    String id = c.getString(2);
                    if ("3".equals(id)) channelItems.put(c.getLong(0), recommendations);
                    if ("1".equals(id)) channelItems.put(c.getLong(0), subscriptions);
                }
            }
            // Ignore channel browsable state: stock Home no longer maintains it.
            if (!channelItems.isEmpty()) {
                try (Cursor c = requireCursor(resolver.query(TvContract.PreviewPrograms.CONTENT_URI,
                        columns("channel_id", "weight"), null, null, null))) {
                    while (c.moveToNext()) {
                        if (!SMARTTUBE.equals(c.getString(8)) || (!c.isNull(7) && c.getInt(7) == 0)) continue;
                        List<Item> shelf = channelItems.get(longColumn(c, "channel_id"));
                        if (shelf == null) continue;
                        Item item = item(c, longColumn(c, "weight"));
                        if (item != null) shelf.add(item);
                    }
                }
            }
            return new Feeds(next, ready(recommendations, 24), ready(subscriptions, 24));
        } catch (RuntimeException error) {
            Feed failure = failedQuery("SmartTube preview feeds", error);
            return new Feeds(next, failure, failure);
        }
    }

    private static Feed watchNext(ContentResolver resolver) {
        List<Item> items = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Cursor c = requireCursor(resolver.query(TvContract.WatchNextPrograms.CONTENT_URI,
                columns("last_engagement_time_utc_millis", "watch_next_type"), null, null, null))) {
            while (c.moveToNext()) {
                if (!SMARTTUBE.equals(c.getString(8))) continue;
                long engaged = longColumn(c, "last_engagement_time_utc_millis");
                long pos = c.isNull(5) ? 0 : c.getLong(5), duration = c.isNull(6) ? 0 : c.getLong(6);
                int type = (int) longColumn(c, "watch_next_type");
                if (!keepContinue(c.getInt(7), type, pos, duration, engaged, now)) continue;
                Item item = item(c, engaged);
                if (item != null) items.add(item);
            }
            return ready(items, 100);
        } catch (RuntimeException error) {
            return failedQuery("SmartTube Watch Next", error);
        }
    }

    private static Cursor requireCursor(Cursor cursor) {
        if (cursor == null) throw new IllegalStateException("TV provider returned no cursor");
        return cursor;
    }

    private static Feed failedQuery(String feed, RuntimeException error) {
        Log.w("TvHome.Shelf", "Unable to read " + feed + ": " + error.getClass().getSimpleName());
        return failed(error instanceof SecurityException ? Status.PERMISSION_REQUIRED : Status.UNAVAILABLE);
    }

    private static Feed failed(Status status) {
        return new Feed(status, Collections.emptyList());
    }

    private static Feed ready(List<Item> items, int max) {
        items.sort((a, b) -> Long.compare(b.order, a.order));
        List<Item> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : items) {
            if (result.size() >= max) break;
            if (seen.add(item.intentUri)) result.add(item);
        }
        return new Feed(Status.READY, result);
    }

    static boolean sameCards(List<Item> previous, List<Item> updated) {
        if (previous.size() != updated.size()) return false;
        for (int i = 0; i < previous.size(); i++) {
            Item a = previous.get(i), b = updated.get(i);
            if (a.progress != b.progress || a.durationMillis != b.durationMillis
                    || !Objects.equals(a.title, b.title) || !Objects.equals(a.image, b.image)
                    || !Objects.equals(a.intentUri, b.intentUri)
                    || !Objects.equals(a.description, b.description)
                    || !Objects.equals(a.packageName, b.packageName)) return false;
        }
        return true;
    }
    private static Item item(Cursor c, long order) {
        String title = firstNonEmpty(c.getString(0), c.getString(1));
        String intent = c.getString(4), pkg = c.getString(8);
        if (title == null || intent == null || pkg == null || !pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return null;
        String episode = c.getString(1);
        if (episode != null && !episode.trim().isEmpty() && !episode.equals(title)) title += " · " + episode;
        return new Item(title, firstNonEmpty(c.getString(3), c.getString(2)), intent, pkg,
                progress(c.isNull(5) ? 0 : c.getLong(5), c.isNull(6) ? 0 : c.getLong(6)), order,
                c.getString(c.getColumnIndexOrThrow(TvContract.Programs.COLUMN_SHORT_DESCRIPTION)),
                c.isNull(6) ? 0 : c.getLong(6));
    }

    private static long longColumn(Cursor cursor, String name) {
        int index = cursor.getColumnIndexOrThrow(name);
        return cursor.isNull(index) ? 0 : cursor.getLong(index);
    }

    private static final class DescriptionMetadata {
        final boolean parsed;
        final String raw, creatorAndUpload, views;
        DescriptionMetadata(boolean parsed, String raw, String creatorAndUpload, String views) {
            this.parsed = parsed; this.raw = raw; this.creatorAndUpload = creatorAndUpload; this.views = views;
        }
    }

    /**
     * SmartTube currently publishes its subtitle as the TV-provider description. Keep the parse
     * deliberately narrow: an unknown segment makes the whole subtitle the creator line, so a
     * locale or format change cannot silently turn statistics into a channel name or lose text.
     */
    private static DescriptionMetadata parseDescription(String description) {
        String raw = description == null ? null : description.trim();
        if (raw == null || raw.isEmpty()) return new DescriptionMetadata(false, null, null, null);
        String[] parts = raw.split("\\s*•\\s*");
        if (parts.length < 2) return new DescriptionMetadata(false, raw, null, null);

        // SmartTube may prefix a badge (for example “4K” or “New”); its own author extraction
        // treats the second segment as the creator when there are four or more segments.
        int creatorIndex = parts.length >= 4 ? 1 : 0;
        String creator = parts[creatorIndex].trim();
        if (creator.isEmpty() || isViewCount(creator) || isUploadDate(creator))
            return new DescriptionMetadata(false, raw, null, null);

        String upload = null, views = null;
        for (int i = creatorIndex + 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (isViewCount(part)) {
                if (views != null) return new DescriptionMetadata(false, raw, null, null);
                views = part;
            } else if (isUploadDate(part)) {
                if (upload != null) return new DescriptionMetadata(false, raw, null, null);
                upload = part;
            } else {
                return new DescriptionMetadata(false, raw, null, null);
            }
        }
        if (upload == null && views == null) return new DescriptionMetadata(false, raw, null, null);
        String creatorAndUpload = upload == null ? creator : creator + " · " + upload;
        return new DescriptionMetadata(true, raw, creatorAndUpload, views);
    }

    private static boolean isViewCount(String value) {
        return value != null && value.matches("(?i).*\\bviews?\\b.*")
                && value.matches(".*\\d.*");
    }

    private static boolean isUploadDate(String value) {
        if (value == null) return false;
        String text = value.toLowerCase(Locale.ROOT).trim();
        return text.matches(".*\\b\\d+\\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?)\\s+ago\\b.*")
                || text.matches(".*\\b(today|yesterday)\\b.*")
                || text.matches(".*\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b.*")
                || text.matches(".*\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}\\b.*");
    }

    private static String formatDuration(long durationMillis) {
        if (durationMillis <= 0) return null;
        long totalSeconds = durationMillis / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, seconds);
    }
    static boolean keepContinue(int browsable, int type, long pos, long duration, long engaged, long now) {
        if (browsable == 0 || (engaged > 0 && engaged < now - MAX_AGE_MS)) return false;
        return type != 0 || duration <= 0 || pos < duration * 0.95;
    }
    static int progress(long positionMs, long durationMs) {
        if (durationMs <= 0 || positionMs <= 0) return -1;
        return (int) Math.max(1, Math.min(100, positionMs * 100.0 / durationMs));
    }
    static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a;
        if (b != null && !b.trim().isEmpty()) return b;
        return null;
    }
}

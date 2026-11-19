package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text from one TikTok post, ready for MP4 title, description, and keyword tags. */
final class PostText {
    private static final Pattern HASHTAG = Pattern.compile("[#＃]([\\p{L}\\p{M}\\p{N}_]{1,100})");
    private static final Pattern TAG_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]{1,100}");
    final String title;
    final String description;
    final String hashtags;

    private PostText(String title, String description, String hashtags) {
        this.title = title;
        this.description = description;
        this.hashtags = hashtags;
    }

    static PostText from(String explicitTitle, String caption, List<String> listedHashtags) {
        String description = limit(caption == null ? "" : caption.trim(), 4096);
        String title = explicitTitle == null ? "" : explicitTitle.trim();
        if (title.isEmpty()) title = HASHTAG.matcher(description).replaceAll("").replaceAll("\\s+", " ").trim();
        title = limit(title, 180);

        Set<String> tags = new LinkedHashSet<>();
        Matcher found = HASHTAG.matcher(description);
        while (found.find() && tags.size() < 100) tags.add("#" + found.group(1));
        if (listedHashtags != null) for (String listed : listedHashtags) {
            if (tags.size() >= 100) break;
            if (listed == null) continue;
            String name = listed.trim().replaceFirst("^[#＃]", "");
            if (TAG_NAME.matcher(name).matches()) tags.add("#" + name);
        }
        return new PostText(title, description, String.join(", ", new ArrayList<>(tags)));
    }

    private static String limit(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}

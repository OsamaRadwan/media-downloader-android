package com.codex.mediadownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Owns app-private, transparent button images imported from Android's document picker. */
final class CustomIconStore {
    private static final int MAX_INPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ICONS = 30;
    private static final String PREFIX = "icon_";
    private final Context context;
    private final SharedPreferences names;
    private String cachedId;
    private long cachedModified;
    private Bitmap cachedBitmap;

    static final class Entry {
        final String id;
        final String name;
        Entry(String id, String name) { this.id = id; this.name = name; }
    }

    CustomIconStore(Context context) {
        this.context = context.getApplicationContext();
        this.names = this.context.getSharedPreferences("custom_icon_names", Context.MODE_PRIVATE);
    }

    static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        File[] files = directory().listFiles();
        if (files != null) for (File file : files) {
            String filename = file.getName();
            if (!file.isFile() || !filename.startsWith(PREFIX) || !filename.endsWith(".png")) continue;
            String id = filename.substring(PREFIX.length(), filename.length() - 4);
            if (validId(id)) {
                String name;
                try { name = names.getString(id, "Custom icon"); }
                catch (ClassCastException invalid) { name = "Custom icon"; }
                entries.add(new Entry(id, name));
            }
        }
        Collections.sort(entries, (left, right) -> left.name.compareToIgnoreCase(right.name));
        return entries;
    }

    Entry importIcon(Uri uri) throws IOException {
        if (list().size() >= MAX_ICONS) throw new IOException("Remove an icon before adding more (limit 30)");
        byte[] input = readBounded(uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(input, 0, input.length, bounds);
        if (bounds.outWidth < 16 || bounds.outHeight < 16 ||
            bounds.outWidth > 4096 || bounds.outHeight > 4096)
            throw new IOException("Choose an image between 16 and 4096 pixels per side");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512)
            options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(input, 0, input.length, options);
        if (bitmap == null) throw new IOException("Image could not be read");
        try {
            if (!hasTransparentPixel(bitmap))
                throw new IOException("Choose an image with a transparent background");
            if (!directory().isDirectory() && !directory().mkdirs())
                throw new IOException("Could not create icon storage");
            String id = UUID.randomUUID().toString();
            File destination = file(id);
            File temporary = new File(directory(), PREFIX + id + ".tmp");
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        throw new IOException("Could not save image");
                    output.getFD().sync();
                }
                if (!temporary.renameTo(destination)) throw new IOException("Could not save image");
            } finally { if (temporary.exists()) temporary.delete(); }
            String name = displayName(uri);
            names.edit().putString(id, name).apply();
            return new Entry(id, name);
        } finally { bitmap.recycle(); }
    }

    Bitmap load(String id) {
        if (!exists(id)) return null;
        File selected = file(id);
        long modified = selected.lastModified();
        if (id.equals(cachedId) && modified == cachedModified && cachedBitmap != null &&
            !cachedBitmap.isRecycled()) return cachedBitmap;
        Bitmap decoded = BitmapFactory.decodeFile(selected.getAbsolutePath());
        cachedId = id;
        cachedModified = modified;
        cachedBitmap = decoded;
        return decoded;
    }

    boolean exists(String id) {
        return validId(id) && file(id).isFile();
    }

    boolean delete(String id) {
        if (!validId(id)) return false;
        File selected = file(id);
        if (!selected.isFile() || !selected.delete()) return false;
        if (id.equals(cachedId)) { cachedId = null; cachedBitmap = null; }
        names.edit().remove(id).apply();
        return true;
    }

    private File directory() { return new File(context.getFilesDir(), "button_icons"); }

    private File file(String id) { return new File(directory(), PREFIX + id + ".png"); }

    private byte[] readBounded(Uri uri) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Image could not be opened");
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_INPUT_BYTES) throw new IOException("Image is larger than 4 MB");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static boolean hasTransparentPixel(Bitmap bitmap) {
        if (!bitmap.hasAlpha()) return false;
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int pixel : row) if ((pixel >>> 24) < 255) return true;
        }
        return false;
    }

    private String displayName(Uri uri) {
        String name = "Custom icon";
        try (Cursor cursor = context.getContentResolver().query(uri,
            new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        } catch (RuntimeException ignored) { }
        name = name.replaceAll("\\.[^.]+$", "").replaceAll("[\\p{Cntrl}]", " ").trim();
        if (name.isEmpty()) name = "Custom icon";
        return name.length() > 40 ? name.substring(0, 40) : name;
    }
}

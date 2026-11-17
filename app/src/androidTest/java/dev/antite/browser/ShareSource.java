package dev.antite.browser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Plays "another app" for e2e scripts: adb shell cannot grant the browser access to a media store file, so a shared
// file comes from here, readable by anyone. content://dev.antite.browser.test.share/NAME gives a 64x64 picture for
// a .png name and the name as text otherwise. Java, not Kotlin: this runs in the test APK's own process, which has
// no Kotlin runtime.
public class ShareSource extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private static String name(Uri uri) {
        String s = uri.getLastPathSegment();
        return s == null ? "" : s;
    }

    private static byte[] bytes(Uri uri) {
        String name = name(uri);
        if (!name.endsWith(".png")) return name.getBytes(StandardCharsets.UTF_8);
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        // A colour of its own per name, so a check can tell one picture from another on the screen.
        b.eraseColor(0xff000000 | (name.hashCode() & 0x00ffffff));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{name(uri), (long) bytes(uri).length});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return name(uri).endsWith(".png") ? "image/png" : "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(getContext().getCacheDir(), "share");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes(uri));
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }
}

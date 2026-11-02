package com.norvex.roleplay;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Provider file mini TANPA dependency — melayani file APK update ke pemasang paket.
 * Hanya melayani folder update milik aplikasi sendiri (aman, tak bisa intip lain).
 */
public class MiniFileProvider extends ContentProvider {

    private static final String AUTH_SUFFIX = ".files";
    private static final int CODE_UPDATE = 1;
    private static final String AUTH = "com.norvex.roleplay.files";
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTH, "update/*", CODE_UPDATE);
    }

    private static String authority(Context ctx) {
        return ctx.getPackageName() + AUTH_SUFFIX;
    }

    public static Uri uriFor(Context ctx, File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(ctx))
                .appendPath("update")
                .appendPath(f.getName())
                .build();
    }

    @Override
    public boolean onCreate() { return true; }

    private File fileFor(Uri uri) throws FileNotFoundException {
        try {
            Context ctx = getContext();
            if (ctx == null || MATCHER.match(uri) != CODE_UPDATE) throw new FileNotFoundException("bad uri");
            String name = uri.getLastPathSegment();
            if (name == null || name.contains("..") || name.contains("/")) throw new FileNotFoundException("bad name");
            File dir = new File(ctx.getExternalFilesDir(null), "update");
            File f = new File(dir, name);
            if (!f.exists()) throw new FileNotFoundException("missing");
            return f;
        } catch (FileNotFoundException e) { throw e; }
        catch (Exception e) { throw new FileNotFoundException("err"); }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = fileFor(uri);
            String[] cols = (projection != null) ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            c.addRow(row);
            return c;
        } catch (Exception e) { return null; }
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        info.exported = false;
        info.grantUriPermissions = true;
        super.attachInfo(context, info);
    }
}

package io.github.prostoodin1.cameradeck;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public class UpdateProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    private File file(Uri uri) { if (!"/update.apk".equals(uri.getPath())) throw new IllegalArgumentException("Unknown file"); return new File(getContext().getFilesDir(), "update.apk"); }
    public String getType(Uri uri) { file(uri); return "application/vnd.android.package-archive"; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { if (!"r".equals(mode)) throw new SecurityException("Read only"); return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY); }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(uri); String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(cols); Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) row[i] = cols[i].equals(OpenableColumns.DISPLAY_NAME) ? "CameraDeck.apk" : cols[i].equals(OpenableColumns.SIZE) ? f.length() : null;
        result.addRow(row); return result;
    }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}

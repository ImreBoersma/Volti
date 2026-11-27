package nl.imreboersma.volti.drive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Another app offering a PDF, as Gmail or Files do: content://nl.imreboersma.volti.test.sharing/NAME is a one-page
 * PDF called NAME. It runs in the test APK's own process, which has no Kotlin runtime (that ships in the app's APK):
 * hence Java, and it makes the PDF itself.
 */
public class SharingAppProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{uri.getLastPathSegment()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new java.io.FileNotFoundException("Can't open a pipe");
        }
        ParcelFileDescriptor write = pipe[1];
        new Thread(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
                PdfDocument doc = new PdfDocument();
                doc.finishPage(doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create()));
                doc.writeTo(out);
                doc.close();
            } catch (IOException ignored) {
                // The reader went away: nothing to do.
            }
        }).start();
        return pipe[0];
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}

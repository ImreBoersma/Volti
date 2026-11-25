package nl.imreboersma.volti.render;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;

/**
 * PDF rendering in the isolated process. Errors come back as SecurityException (password-protected)
 * or IllegalStateException (anything else); a crash or hang in PDFium kills the service process, which
 * the caller sees as a DeadObjectException.
 */
interface IPdfRenderer {
    /** Opens [fd], which the service takes over. Returns a document id. */
    int open(in ParcelFileDescriptor fd);

    int pageCount(int doc);

    /**
     * Renders page [index] on white, as large as fits in maxWidth x maxHeight, into [pixels] as ARGB_8888
     * rows. Returns {width, height}.
     */
    int[] renderPage(int doc, int index, int maxWidth, int maxHeight, in SharedMemory pixels);

    void close(int doc);
}

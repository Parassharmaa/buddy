package dev.buddy.pdf;
import android.os.ParcelFileDescriptor;
import dev.buddy.pdf.IPdfReply;
interface IPdfReader {
    oneway void extract(String requestId, in ParcelFileDescriptor input, IPdfReply reply);
    oneway void cancel(String requestId);
}

package dev.buddy.shell;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;
interface IBuddyHelper {
    Bundle capabilities();
    Bundle acquireLease(IBinder owner, in byte[] challenge);
    void renewLease(String lease, in byte[] challenge);
    String createDisplay(String lease, int width, int height, int density, in Surface surface);
    void releaseDisplay(String lease, String displayToken);
    int launchFixture(String lease, String displayToken, String requestId);
    void tap(String lease, String displayToken, String requestId, int x, int y);
    void releaseLease(String lease);
    void disconnect();
    void takeOverFixture(String lease, String displayToken, String requestId);
    void returnFixture(String lease, String displayToken, String requestId);
    Bundle displayState(String lease, String displayToken);
    void prepareAuthorization(in byte[] challenge);
    void completeAuthorization(in byte[] challenge);
}

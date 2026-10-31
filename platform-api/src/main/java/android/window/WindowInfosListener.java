package android.window;

import android.util.Pair;
import android.view.InputWindowHandle;

// Android 14+ contract: frameworks/base/core/java/android/window/WindowInfosListener.java.
// Compile-only; the shell process uses Android's implementation at runtime.
public abstract class WindowInfosListener {
    public static final class DisplayInfo {}

    public WindowInfosListener() {
        throw new UnsupportedOperationException("Platform declaration only");
    }

    public abstract void onWindowInfosChanged(InputWindowHandle[] windows, DisplayInfo[] displays);

    public Pair<InputWindowHandle[], DisplayInfo[]> register() {
        throw new UnsupportedOperationException("Platform declaration only");
    }

    public void unregister() {
        throw new UnsupportedOperationException("Platform declaration only");
    }
}

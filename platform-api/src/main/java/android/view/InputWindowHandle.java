package android.view;

// Compile-only Android platform declarations. No implementation is packaged in Buddy.
public final class InputWindowHandle {
    public int displayId;
    public int ownerUid;
    public int inputConfig;
    public int layoutParamsFlags;
    public int layoutParamsType;

    private InputWindowHandle() {
        throw new UnsupportedOperationException("Platform declaration only");
    }
}

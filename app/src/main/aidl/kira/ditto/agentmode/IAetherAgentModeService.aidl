package kira.ditto.agentmode;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface IAetherAgentModeService {
    int createDisplay(String name, int width, int height, int density, in Surface surface) = 1;
    int createOwnedDisplay(String name, int width, int height, int density) = 2;
    void attachPreviewSurface(int displayId, in Surface surface) = 3;
    void detachPreviewSurface(int displayId) = 4;
    void releaseDisplay(int displayId) = 5;
    void launchPackage(String packageName, int displayId) = 6;
    void runInputCommand(String command) = 7;
    void tap(int displayId, int x, int y) = 8;
    void swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) = 9;
    void key(int displayId, String keyCode) = 10;
    void text(int displayId, String text) = 11;
    void captureImageToFd(int displayId, in ParcelFileDescriptor output, int maxEdge, int quality) = 12;
    void clearText(int displayId) = 16;
    String listDisplaysJson() = 13;
    String listInstalledAppsJson() = 14;
    void launchHomeOnDisplay(int displayId) = 15;
    String dumpUiTree(int displayId) = 17;
    void undo(int displayId) = 18;
    void hideIme(int displayId) = 19;
    boolean waitUntilLayoutStable(int displayId, int quietMs, int timeoutMs) = 20;
    String dumpUiTreePaged(int displayId, String query, String region, int offset, int limit) = 21;
    boolean clickNode(int displayId, String query) = 22;
    void longPress(int displayId, int x, int y, int durationMs) = 23;
    void doubleTap(int displayId, int x, int y) = 24;
    void pinch(int displayId, int cx, int cy, int startSpan, int endSpan, int durationMs) = 25;
    boolean waitForLabel(int displayId, String label, int timeoutMs) = 26;
    ParcelFileDescriptor startInternalAudioCapture(int sampleRateHz) = 27;
    void stopInternalAudioCapture() = 28;
    int adoptDefaultDisplay() = 29;
    boolean packageVisibleOnDisplay(int displayId, String packageName) = 30;
    void destroy() = 16777114;
}

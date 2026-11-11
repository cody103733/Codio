package dev.jdtech.mpv;

import android.content.Context;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Java version of libmpv-android's MPVLib (MIT, github.com/jarnedemeulemeester/libmpv-android).
 * The published classes.jar is Java 21 bytecode, which the Android dexer available here can't read,
 * so this mirrors its JNI surface 1:1 (same class name, native methods and callbacks used by libplayer.so).
 */
public final class MPVLib {
    static {
        System.loadLibrary("mpv");
        System.loadLibrary("player");
    }

    public interface EventObserver {
        void eventProperty(String property);
        void eventProperty(String property, long value);
        void eventProperty(String property, double value);
        void eventProperty(String property, boolean value);
        void eventProperty(String property, String value);
        void event(int eventId);
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    private long nativeInstance;
    private final List<EventObserver> observers = new ArrayList<>();
    private final List<LogObserver> logObservers = new ArrayList<>();

    private MPVLib() {}

    public static MPVLib create(Context context) {
        Context app = context.getApplicationContext();
        MPVLib lib = new MPVLib();
        long ptr = lib.nativeCreate(lib, app);
        if (ptr == 0) return null;
        lib.nativeInstance = ptr;
        return lib;
    }

    private void checkCreated() {
        if (nativeInstance == 0) throw new IllegalStateException("mpv instance is destroyed");
    }

    private native long nativeCreate(MPVLib self, Context context);
    private native void nativeInit(long instance);
    private native void nativeDestroy(long instance);
    private native void nativeAttachSurface(long instance, Surface surface);
    private native void nativeDetachSurface(long instance);
    private native void nativeCommand(long instance, String[] cmd);
    private native int nativeSetOptionString(long instance, String name, String value);
    private native Integer nativeGetPropertyInt(long instance, String name);
    private native void nativeSetPropertyInt(long instance, String name, int value);
    private native Double nativeGetPropertyDouble(long instance, String name);
    private native void nativeSetPropertyDouble(long instance, String name, double value);
    private native Boolean nativeGetPropertyBoolean(long instance, String name);
    private native void nativeSetPropertyBoolean(long instance, String name, boolean value);
    private native String nativeGetPropertyString(long instance, String name);
    private native void nativeSetPropertyString(long instance, String name, String value);
    private native void nativeObserveProperty(long instance, String name, int format);

    public void init() { checkCreated(); nativeInit(nativeInstance); }

    public void destroy() {
        checkCreated();
        nativeDestroy(nativeInstance);
        nativeInstance = 0;
    }

    public void attachSurface(Surface surface) { checkCreated(); nativeAttachSurface(nativeInstance, surface); }
    public void detachSurface() { checkCreated(); nativeDetachSurface(nativeInstance); }
    public void command(String[] cmd) { checkCreated(); nativeCommand(nativeInstance, cmd); }
    public int setOptionString(String name, String value) { checkCreated(); return nativeSetOptionString(nativeInstance, name, value); }
    public Integer getPropertyInt(String name) { checkCreated(); return nativeGetPropertyInt(nativeInstance, name); }
    public void setPropertyInt(String name, int value) { checkCreated(); nativeSetPropertyInt(nativeInstance, name, value); }
    public Double getPropertyDouble(String name) { checkCreated(); return nativeGetPropertyDouble(nativeInstance, name); }
    public void setPropertyDouble(String name, double value) { checkCreated(); nativeSetPropertyDouble(nativeInstance, name, value); }
    public Boolean getPropertyBoolean(String name) { checkCreated(); return nativeGetPropertyBoolean(nativeInstance, name); }
    public void setPropertyBoolean(String name, boolean value) { checkCreated(); nativeSetPropertyBoolean(nativeInstance, name, value); }
    public String getPropertyString(String name) { checkCreated(); return nativeGetPropertyString(nativeInstance, name); }
    public void setPropertyString(String name, String value) { checkCreated(); nativeSetPropertyString(nativeInstance, name, value); }
    public void observeProperty(String name, int format) { checkCreated(); nativeObserveProperty(nativeInstance, name, format); }

    public void addObserver(EventObserver o) { synchronized (observers) { observers.add(o); } }
    public void removeObserver(EventObserver o) { synchronized (observers) { observers.remove(o); } }
    public void addLogObserver(LogObserver o) { synchronized (logObservers) { logObservers.add(o); } }
    public void removeLogObserver(LogObserver o) { synchronized (logObservers) { logObservers.remove(o); } }

    // ---- Called from libplayer.so's event thread.

    public void eventProperty(String property, long value) {
        for (EventObserver o : snapshot()) o.eventProperty(property, value);
    }

    public void eventProperty(String property, double value) {
        for (EventObserver o : snapshot()) o.eventProperty(property, value);
    }

    public void eventProperty(String property, boolean value) {
        for (EventObserver o : snapshot()) o.eventProperty(property, value);
    }

    public void eventProperty(String property, String value) {
        for (EventObserver o : snapshot()) o.eventProperty(property, value);
    }

    public void eventProperty(String property) {
        for (EventObserver o : snapshot()) o.eventProperty(property);
    }

    public void event(int eventId) {
        for (EventObserver o : snapshot()) o.event(eventId);
    }

    public void logMessage(String prefix, int level, String text) {
        List<LogObserver> copy;
        synchronized (logObservers) { copy = new ArrayList<>(logObservers); }
        for (LogObserver o : copy) o.logMessage(prefix, level, text);
    }

    private List<EventObserver> snapshot() {
        synchronized (observers) { return new ArrayList<>(observers); }
    }
}

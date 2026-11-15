package com.codio.tv;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

/** Message shapes used by Stremio's desktop shell (see stremio-shell-ng src/stremio_app/ipc.rs). */
final class ShellProtocol {
    static final int TYPE_SIGNAL = 1;
    static final int TYPE_INIT = 3;
    static final int TYPE_INVOKE = 6;

    private ShellProtocol() {}

    static String handshake(String shellVersion, boolean hasMpv) throws Exception {
        JSONArray props = new JSONArray();
        props.put(new JSONArray());
        props.put(prop("shellVersion", shellVersion));
        props.put(prop("gpuVideoProcessing", "false"));
        props.put(prop("cacheDirectoryPicker", "false"));
        props.put(prop("streamingServerUrl", ""));
        props.put(prop("nativeInterfaceScale", "false"));
        props.put(prop("nativeAssSubtitles", hasMpv ? "true" : "false"));
        JSONObject transport = new JSONObject();
        transport.put("properties", props);
        transport.put("signals", new JSONArray());
        transport.put("methods", new JSONArray().put(new JSONArray().put("onEvent").put("")));
        JSONObject data = new JSONObject();
        data.put("transport", transport);
        JSONObject o = new JSONObject();
        o.put("id", 0);
        o.put("object", "transport");
        o.put("type", TYPE_INIT);
        o.put("data", data);
        return o.toString();
    }

    private static JSONArray prop(String name, String value) {
        return new JSONArray().put("").put(name).put("").put(value);
    }

    static String signal(String name, Object arg) throws Exception {
        JSONArray args = new JSONArray().put(name);
        if (arg != null) args.put(arg);
        JSONObject o = new JSONObject();
        o.put("id", 1);
        o.put("object", "transport");
        o.put("type", TYPE_SIGNAL);
        o.put("args", args);
        return o.toString();
    }

    static String propChange(String name, Object data) throws Exception {
        return signal("mpv-prop-change", new JSONObject().put("name", name).put("data", data));
    }

    static String videoReady(int loadId, boolean ready) throws Exception {
        return signal("mpv-event-video-ready", new JSONObject().put("loadId", loadId).put("ready", ready));
    }

    /** What the web player asks of a shell; each call arrives on the shell's worker thread. */
    interface Commands {
        void observe(String name);

        void setProp(String name, Object value);

        void command(String[] cmd);
    }

    /** Routes one invoke from the web UI to the shell's worker thread. Returns true if it was a player message. */
    static boolean dispatch(String method, Object params, Handler worker, final Commands to) {
        switch (method) {
            case "mpv-observe-prop":
                if (params instanceof String) {
                    final String name = (String) params;
                    worker.post(new Runnable() {
                        @Override public void run() {
                            to.observe(name);
                        }
                    });
                }
                return true;
            case "mpv-set-prop":
                if (params instanceof JSONArray && ((JSONArray) params).length() >= 2) {
                    final JSONArray a = (JSONArray) params;
                    final String name = a.optString(0);
                    final Object value = a.opt(1);
                    worker.post(new Runnable() {
                        @Override public void run() {
                            to.setProp(name, value);
                        }
                    });
                }
                return true;
            case "mpv-command":
                if (params instanceof JSONArray) {
                    final JSONArray a = (JSONArray) params;
                    final String[] cmd = new String[a.length()];
                    for (int i = 0; i < cmd.length; i++) cmd[i] = String.valueOf(a.opt(i));
                    worker.post(new Runnable() {
                        @Override public void run() {
                            to.command(cmd);
                        }
                    });
                }
                return true;
            case "mpv-set-gpu-video-processing":
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- values as mpv sends them

    static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = String.valueOf(v);
        return "yes".equals(s) || "true".equals(s);
    }

    /** A track id, or -1 for "no". */
    static int trackId(Object v) {
        if (v == null || v == JSONObject.NULL) return -1;
        String s = String.valueOf(v);
        if ("no".equals(s) || "false".equals(s)) return -1;
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static double num(Object v, double fallback) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

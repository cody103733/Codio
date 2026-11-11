package com.codio.tv;

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
}

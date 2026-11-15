package com.codio.tv.ui;

import org.json.JSONObject;

/** Messages to and from Codio's core (the engine in the hidden web page), shared by the TV and phone screens. */
final class Core {
    private Core() {}

    /** A Ctx action: {"action":"Ctx","args":{"action":name,"args":args}}. */
    static JSONObject ctx(String name, Object args) {
        try {
            JSONObject inner = new JSONObject().put("action", name);
            if (args != null) inner.put("args", args);
            return new JSONObject().put("action", "Ctx").put("args", inner);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static JSONObject login(String email, String password) {
        try {
            return new JSONObject().put("type", "Login").put("email", email).put("password", password);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** The short message to show for a core event ("AddonInstalled", "Error:<event>"...), or null for none. */
    static String toastFor(String event, String message) {
        if (event.startsWith("Error:")) {
            String ev = event.substring(6);
            if (ev.equals("UserPulledFromAPI") || ev.equals("LibrarySyncWithAPIPlanned") || ev.equals("UserAddonsLocked")) return null;
            return message != null && !message.isEmpty() ? message : "Something went wrong";
        }
        switch (event) {
            case "AddonInstalled": return "Addon installed";
            case "AddonUninstalled": return "Addon removed";
            case "UserAuthenticated": return "Signed in";
            case "UserLoggedOut": return "Signed out";
            default: return null;
        }
    }
}

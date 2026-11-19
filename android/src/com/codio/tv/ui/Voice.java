package com.codio.tv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Voice search on the TV. Three ways, best first:
 * 1. The system's voice prompt (RecognizerIntent): Google TV and Android TV have it, it shows its own "Speak now" screen.
 * 2. A speech service without a prompt (SpeechRecognizer): Codio draws its own "Listening" card. Needs the mic permission.
 * 3. Neither (most Fire TVs): no mic key on the Search screen, and the remote's search/mic key says voice isn't available.
 */
public final class Voice {
    public static final int REQ_PROMPT = 7301, REQ_MIC = 7302;
    private static final String TAG = "CodioVoice";
    private static final String MIC = "android.permission.RECORD_AUDIO";

    /** What the Search screen hears back. */
    interface Listener {
        void heard(String text);

        /** Something to tell the viewer (didn't catch that, not available). */
        void failed(String message);
    }

    private static Boolean prompt, service;
    private static Listener pending;
    private static Activity pendingActivity;
    private static SpeechRecognizer recognizer;
    /** Shown on Codio's own Listening card (way 2). */
    static volatile boolean listening = false;
    static volatile String partial = "";
    static volatile float level = 0;

    private Voice() {}

    private static Intent promptIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Search movies and shows");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return i;
    }

    private static boolean hasPrompt(Context ctx) {
        if (prompt == null) {
            try {
                prompt = !ctx.getPackageManager().queryIntentActivities(promptIntent(), 0).isEmpty();
            } catch (RuntimeException e) {
                prompt = false;
            }
        }
        return prompt;
    }

    private static boolean hasService(Context ctx) {
        if (service == null) {
            try {
                service = SpeechRecognizer.isRecognitionAvailable(ctx);
            } catch (RuntimeException e) {
                service = false;
            }
        }
        return service;
    }

    /** True when this TV can do voice search at all (the mic key is shown). */
    static boolean available(Context ctx) {
        return hasPrompt(ctx) || hasService(ctx);
    }

    static void listen(Context ctx, Listener l) {
        if (!(ctx instanceof Activity)) {
            l.failed("Voice search isn't available on this TV");
            return;
        }
        Activity a = (Activity) ctx;
        cancel();
        pending = l;
        pendingActivity = a;
        if (hasPrompt(a)) {
            try {
                a.startActivityForResult(promptIntent(), REQ_PROMPT);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "voice prompt failed", e);
                prompt = false;
            }
        }
        if (hasService(a)) {
            if (a.checkSelfPermission(MIC) == PackageManager.PERMISSION_GRANTED) startService(a);
            else a.requestPermissions(new String[]{MIC}, REQ_MIC);
            return;
        }
        pending = null;
        l.failed("Voice search isn't available on this TV");
    }

    /** From MainActivity.onActivityResult. */
    public static boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_PROMPT) return false;
        Listener l = pending;
        pending = null;
        if (l == null) return true;
        String text = null;
        if (res == Activity.RESULT_OK && data != null) {
            ArrayList<String> all = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (all != null && !all.isEmpty()) text = all.get(0);
        }
        if (text != null && !text.trim().isEmpty()) l.heard(text.trim());
        // Cancelled with Back: nothing to say.
        return true;
    }

    /** From MainActivity.onRequestPermissionsResult. */
    public static boolean onPermissions(int req, int[] results) {
        if (req != REQ_MIC) return false;
        Activity a = pendingActivity;
        if (pending == null || a == null) return true;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startService(a);
        else {
            Listener l = pending;
            pending = null;
            l.failed("Codio needs the microphone for voice search");
        }
        return true;
    }

    private static void startService(Activity a) {
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(a);
        } catch (RuntimeException e) {
            recognizer = null;
        }
        if (recognizer == null) {
            finish(null, "Voice search isn't available on this TV");
            return;
        }
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
                kick();
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {
                level = Math.max(0, Math.min(1, (rmsdB + 2) / 12f));
            }

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                boolean missed = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) finish(null, "Codio needs the microphone for voice search");
                else finish(null, missed ? "Didn't catch that. Try again." : "Voice search isn't working right now");
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> all = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = all != null && !all.isEmpty() ? all.get(0) : null;
                if (text == null || text.trim().isEmpty()) finish(null, "Didn't catch that. Try again.");
                else finish(text.trim(), null);
            }

            @Override
            public void onPartialResults(Bundle results) {
                ArrayList<String> all = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (all != null && !all.isEmpty()) partial = all.get(0);
                kick();
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
        Intent i = promptIntent();
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, a.getPackageName());
        partial = "";
        level = 0;
        listening = true;
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "listen failed", e);
            finish(null, "Voice search isn't working right now");
        }
        kick();
    }

    private static void finish(String text, String error) {
        listening = false;
        partial = "";
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
            recognizer = null;
        }
        Listener l = pending;
        pending = null;
        if (l != null) {
            if (text != null) l.heard(text);
            else if (error != null) l.failed(error);
        }
        kick();
    }

    /** Back on the Listening card, or the Search screen closed. */
    static void cancel() {
        boolean was = listening;
        pending = null;
        if (recognizer != null || was) finish(null, null);
    }

    private static TvView view;

    static void attach(TvView tv) {
        view = tv;
    }

    private static void kick() {
        TvView v = view;
        if (v != null) v.post(new Runnable() {
            @Override
            public void run() {
                v.kick();
            }
        });
    }
}

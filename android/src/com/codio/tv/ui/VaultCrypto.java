package com.codio.tv.ui;

import android.util.Base64;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.text.Normalizer;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The sync passcode's crypto (see SYNC.md), on its own so it can be checked against sync/vector.json on a plain JVM:
 * PBKDF2-HMAC-SHA256 (32-byte key) from the trimmed, NFC passcode, then AES-256-GCM with a 12-byte nonce, the 16-byte
 * tag after the ciphertext. Same as Windows (vault.ts) and iPhone (VaultCrypto.swift).
 */
final class VaultCrypto {
    private VaultCrypto() {
    }

    public static String normalise(String passcode) {
        return Normalizer.normalize(passcode == null ? "" : passcode.trim(), Normalizer.Form.NFC);
    }

    /** PBKDF2-HMAC-SHA256, 32 bytes. Written out so it runs on the fast native HMAC on every Android. */
    static byte[] deriveKey(String passcode, byte[] salt, int iterations) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(normalise(passcode).getBytes("UTF-8"), "HmacSHA256"));
        mac.update(salt);
        mac.update(new byte[]{0, 0, 0, 1});
        byte[] u = mac.doFinal();
        byte[] out = u.clone();
        for (int i = 1; i < iterations; i++) {
            mac.update(u);
            mac.doFinal(u, 0);
            for (int j = 0; j < 32; j++) out[j] ^= u[j];
        }
        return out;
    }

    static JSONObject seal(byte[] key, String salt, int iter, JSONObject items) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        return seal(key, salt, iter, items, iv);
    }

    static JSONObject seal(byte[] key, String salt, int iter, JSONObject items, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] data = c.doFinal(new JSONObject().put("v", 1).put("items", items).toString().getBytes("UTF-8"));
        return new JSONObject().put("v", 1).put("kdf", "pbkdf2-sha256").put("iter", iter).put("salt", salt)
                .put("iv", b64(iv)).put("data", b64(data));
    }

    /** The items in the box, or null when this key doesn't open it. */
    static JSONObject open(byte[] key, JSONObject box) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, unb64(box.getString("iv"))));
            JSONObject o = new JSONObject(new String(c.doFinal(unb64(box.getString("data"))), "UTF-8"));
            JSONObject items = o.optJSONObject("items");
            return items != null ? items : new JSONObject();
        } catch (Exception e) {
            return null;
        }
    }

    static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    static byte[] unb64(String s) {
        return Base64.decode(s, Base64.DEFAULT);
    }
}

package com.codio.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codio.tv.ui.VaultCrypto;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * First-launch access password (lock/README.md). Only active when the APK carries assets/access-lock.json
 * (a salt and a PBKDF2-SHA256 hash, never the password). Once the right password is typed the device stays
 * unlocked: the pref remembers which lock it opened, so a new password locks it again.
 * To remove: delete lock/access-lock.json (this class then does nothing), or delete this class, its
 * manifest entries and the LockActivity.redirect line in MainActivity.
 */
public final class LockActivity extends Activity {
    private static final String PREF = "access_unlocked";

    private static JSONObject lock(Context c) {
        try (InputStream in = c.getAssets().open("access-lock.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            JSONObject j = new JSONObject(out.toString("UTF-8"));
            return j.optString("hash").isEmpty() ? null : j;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the lock screen was opened instead; the caller returns from onCreate. */
    static boolean redirect(Activity a) {
        JSONObject j = lock(a);
        if (j == null) return false;
        SharedPreferences p = a.getSharedPreferences("codio", MODE_PRIVATE);
        if (j.optString("hash").equals(p.getString(PREF, null))) return false;
        Intent i = new Intent(a, LockActivity.class);
        Intent orig = a.getIntent();
        if (orig != null) i.putExtra("next", orig);
        a.startActivity(i);
        a.overridePendingTransition(0, 0);
        a.finish();
        return true;
    }

    private EditText field;
    private TextView message;
    private Button button;
    private boolean checking;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        float dp = getResources().getDisplayMetrics().density;
        boolean phone = BuildConfig.PHONE;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xFF0B0B0B);
        int pad = (int) (24 * dp);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Enter the access password");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, phone ? 22 : 28);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("You only need to do this once on this device.");
        sub.setTextColor(0xFFB3B3B3);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, phone ? 15 : 18);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) (8 * dp), 0, (int) (24 * dp));
        col.addView(sub);

        field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        field.setHint("Password");
        field.setHintTextColor(0xFF8C8C8C);
        field.setTextColor(Color.WHITE);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, phone ? 17 : 22);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadius(10 * dp);
        bg.setStroke((int) Math.max(1, dp), 0xFF38383A);
        field.setBackground(bg);
        int fp = (int) (14 * dp);
        field.setPadding(fp, fp, fp, fp);
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                check();
                return true;
            }
        });
        int width = (int) ((phone ? 320 : 420) * dp);
        col.addView(field, new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));

        button = new Button(this);
        button.setText("Unlock");
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, phone ? 17 : 22);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        final GradientDrawable red = new GradientDrawable();
        red.setColor(0xFFE50914);
        red.setCornerRadius(10 * dp);
        button.setBackground(red);
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                red.setStroke(has ? (int) (3 * dp) : 0, Color.WHITE);
            }
        });
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                check();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(width, (int) ((phone ? 48 : 56) * dp));
        blp.topMargin = (int) (16 * dp);
        col.addView(button, blp);

        message = new TextView(this);
        message.setTextColor(0xFFFF5A5F);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, phone ? 15 : 18);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, (int) (16 * dp), 0, 0);
        col.addView(message);

        setContentView(col);
        field.requestFocus();
    }

    private void check() {
        if (checking) return;
        final JSONObject j = lock(this);
        if (j == null) {
            open("");
            return;
        }
        final String typed = field.getText().toString();
        if (VaultCrypto.normalise(typed).isEmpty()) {
            message.setText("Type the password first.");
            return;
        }
        checking = true;
        button.setText("Checking…");
        message.setText("");
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    byte[] got = VaultCrypto.deriveKey(typed, VaultCrypto.unb64(j.getString("salt")), j.optInt("iter", 300000));
                    ok = MessageDigest.isEqual(got, VaultCrypto.unb64(j.getString("hash")));
                } catch (Exception e) {
                    ok = false;
                }
                final boolean right = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        button.setText("Unlock");
                        if (right) {
                            open(j.optString("hash"));
                        } else {
                            message.setText("That password isn't right.");
                            field.selectAll();
                            field.requestFocus();
                        }
                    }
                });
            }
        }, "codio-lock").start();
    }

    private void open(String hash) {
        if (!hash.isEmpty()) getSharedPreferences("codio", MODE_PRIVATE).edit().putString(PREF, hash).commit();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        Intent next = getIntent().getParcelableExtra("next");
        Intent i = next != null ? new Intent(next) : new Intent();
        i.setClass(this, MainActivity.class);
        startActivity(i);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishAffinity();
    }
}

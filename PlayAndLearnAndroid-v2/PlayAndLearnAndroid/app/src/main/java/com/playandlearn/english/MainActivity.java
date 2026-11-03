package com.playandlearn.english;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.VideoView;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int REQUEST_RECORD_AUDIO = 41;
    /** اگر true باشد با لمس صفحه می‌شود فیلم ابتدایی را رد کرد. */
    private static final boolean ALLOW_SKIP_INTRO = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView webView;
    private VideoView videoView;
    private AlertDialog exitDialog;
    private boolean gameStarted = false, introRemoved = false, videoWasPlaying = false, pageReady = false;

    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private String pendingWord = null, pendingGrammar = null, lastRecognizedText = "";
    private boolean modelReady = false, listening = false, partialSent = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        applyImmersive();

        setupWebView();
        LibVosk.setLogLevel(LogLevel.WARN);
        loadOfflineModel();   // هم‌زمان با فیلم ابتدایی آماده می‌شود
        startIntro();
    }

    // ---------------- تمام‌صفحه ----------------
    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    // ---------------- فیلم ابتدایی ----------------
    private void startIntro() {
        int resId = getResources().getIdentifier("intro", "raw", getPackageName());
        if (resId == 0) { startGame(); return; }
        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            try { fitCover(mp.getVideoWidth(), mp.getVideoHeight()); } catch (Exception ignored) { }
            videoView.start();
        });
        videoView.setOnCompletionListener(mp -> startGame());
        videoView.setOnErrorListener((mp, what, extra) -> { startGame(); return true; });
        if (ALLOW_SKIP_INTRO) videoView.setOnClickListener(v -> startGame());
        videoView.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + resId));
    }

    /** فیلم کل صفحه را پر می‌کند (بدون نوار سیاه؛ لبه‌ها کمی بریده می‌شود). */
    private void fitCover(int vw, int vh) {
        int pw = root.getWidth(), ph = root.getHeight();
        if (pw == 0 || ph == 0 || vw == 0 || vh == 0 || videoView == null) return;
        float s = Math.max(pw / (float) vw, ph / (float) vh);
        videoView.setLayoutParams(new FrameLayout.LayoutParams(
                Math.round(vw * s), Math.round(vh * s), Gravity.CENTER));
    }

    private void startGame() {
        if (gameStarted || webView == null) return;
        gameStarted = true;
        webView.loadUrl("file:///android_asset/index.html");
        handler.postDelayed(this::removeIntro, 10000);   // اگر صفحه دیر لود شد
    }

    private void removeIntro() {
        if (introRemoved) return;
        introRemoved = true;
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (Exception ignored) { }
            root.removeView(videoView);
            videoView = null;
        }
    }

    // ---------------- وب‌ویو ----------------
    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);   // پخش صدا بدون ضربه
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                handler.postDelayed(MainActivity.this::removeIntro, 200);
                sendStatus(modelReady ? "موتور تشخیص گفتار آفلاین آماده است."
                        : "در حال آماده‌سازی موتور تشخیص گفتار آفلاین...");
            }
        });
        webView.addJavascriptInterface(new VoskBridge(), "VoskBridge");
        root.addView(webView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    // ---------------- دکمه‌ی برگشت (مثلث) ----------------
    @Override
    public void onBackPressed() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        exitDialog = new AlertDialog.Builder(this)
                .setTitle("خروج از برنامه")
                .setMessage("می‌خوای از برنامه خارج بشی؟")
                .setPositiveButton("خروج", (d, w) -> finish())
                .setNegativeButton("ادامه بازی", null)
                .setOnDismissListener(d -> applyImmersive())
                .create();
        exitDialog.show();
    }

    // ---------------- تشخیص گفتار آفلاین (Vosk) ----------------
    private void loadOfflineModel() {
        StorageService.unpack(this, "model-en-us", "vosk-model",
                loadedModel -> {
                    model = loadedModel;
                    modelReady = true;
                    sendStatus("موتور تشخیص گفتار آفلاین آماده است.");
                    if (pendingWord != null) startWhenReady();
                },
                exception -> {
                    modelReady = false;
                    sendError("بارگذاری مدل گفتار ناموفق بود: " + exception.getMessage());
                });
    }

    private class VoskBridge {
        @JavascriptInterface
        public void startListening(String word) { begin(word, null); }

        @JavascriptInterface
        public void startListeningWithGrammar(String word, String grammarJson) { begin(word, grammarJson); }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(() -> { pendingWord = null; listening = false; stopRecognition(); });
        }

        private void begin(String word, String grammar) {
            pendingWord = word == null ? "" : word.trim();
            pendingGrammar = (grammar == null || grammar.trim().isEmpty()) ? null : grammar;
            runOnUiThread(() -> {
                if (pendingWord.isEmpty()) { sendError("واژه‌ای برای تشخیص دریافت نشد."); return; }
                if (Build.VERSION.SDK_INT >= 23
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                    sendStatus("برای استفاده از میکروفون، اجازه ضبط صدا را تأیید کن.");
                    return;
                }
                startWhenReady();
            });
        }
    }

    private void startWhenReady() {
        if (pendingWord == null) return;
        if (!modelReady || model == null) {
            sendStatus("مدل گفتار هنوز آماده نشده؛ چند لحظه صبر کن...");
            return;
        }
        stopRecognition();
        lastRecognizedText = "";
        partialSent = false;
        try {
            // اگر فهرست واژه‌های درس رسیده باشد، تشخیص فقط بین همان واژه‌ها انجام می‌شود (دقت بیشتر).
            recognizer = (pendingGrammar != null)
                    ? new Recognizer(model, 16000.0f, pendingGrammar)
                    : new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            listening = true;
            sendStatus("در حال گوش دادن؛ حالا واژه را تلفظ کن...");
            speechService.startListening(this, 5000);
        } catch (IOException | RuntimeException e) {
            listening = false;
            stopRecognition();
            sendError("شروع میکروفون ممکن نشد: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWhenReady();
            } else {
                sendError("اجازه میکروفون داده نشد. برای بازی تلفظ باید اجازه ضبط صدا را فعال کنی.");
            }
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String text = extractText(hypothesis);
        if (!text.isEmpty()) finishWithResult(text);   // بلافاصله بعد از تشخیص، نتیجه را بده
    }

    @Override
    public void onPartialResult(String hypothesis) {
        if (listening && !partialSent) { partialSent = true; sendStatus("صدایت دریافت شد؛ کمی صبر کن..."); }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        String text = extractText(hypothesis);
        finishWithResult(text.isEmpty() ? lastRecognizedText : text);
    }

    @Override
    public void onTimeout() {
        String text = lastRecognizedText;
        try {
            if (recognizer != null) {
                String t = extractText(recognizer.getFinalResult());
                if (!t.isEmpty()) text = t;
            }
        } catch (Exception ignored) { }
        finishWithResult(text);
    }

    @Override
    public void onError(Exception e) {
        if (!listening) return;
        listening = false;
        stopRecognition();
        sendError("خطا در تشخیص صدا: " + (e == null ? "نامشخص" : e.getMessage()));
    }

    private String extractText(String json) {
        try { return new JSONObject(json).optString("text", "").trim(); }
        catch (Exception e) { return ""; }
    }

    private void finishWithResult(String text) {
        if (!listening) return;   // هر نشست فقط یک بار نتیجه می‌دهد
        listening = false;
        stopRecognition();
        pendingWord = null;
        if (text == null || text.trim().isEmpty()) {
            sendError("صدایی تشخیص داده نشد. دوباره تلاش کن.");
        } else {
            sendResult(text.trim());
        }
    }

    private void stopRecognition() {
        if (speechService != null) {
            try { speechService.stop(); speechService.shutdown(); } catch (Exception ignored) { }
            speechService = null;
        }
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void runJs(String js) {
        runOnUiThread(() -> { if (webView != null && pageReady) webView.evaluateJavascript(js, null); });
    }
    private void sendStatus(String m) { runJs("if(window.onVoskStatus)window.onVoskStatus(" + JSONObject.quote(m) + ");"); }
    private void sendError(String m)  { runJs("if(window.onVoskError)window.onVoskError(" + JSONObject.quote(m) + ");"); }
    private void sendResult(String t) { runJs("if(window.onVoskResult)window.onVoskResult(" + JSONObject.quote(t) + ");"); }

    // ---------------- چرخه‌ی عمر ----------------
    @Override
    protected void onPause() {
        if (videoView != null && videoView.isPlaying()) { videoView.pause(); videoWasPlaying = true; }
        if (webView != null) webView.onPause();
        listening = false;
        stopRecognition();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (videoWasPlaying && videoView != null) { videoView.start(); videoWasPlaying = false; }
        applyImmersive();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        listening = false;
        stopRecognition();
        if (model != null) { try { model.close(); } catch (Exception ignored) { } model = null; }
        if (videoView != null) { try { videoView.stopPlayback(); } catch (Exception ignored) { } }
        if (webView != null) {
            webView.removeJavascriptInterface("VoskBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

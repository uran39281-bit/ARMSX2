package dev.aether.preview;

import android.content.Intent;
import android.content.ClipData;
import android.content.ComponentName;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Black Ice home interface for the ARMSX2 core included in this application. */
public final class MainActivity extends Activity {
    private WebView web;
    private CoverManager covers;
    private ThemeMusic music;
    private UiSounds sounds;
    private PlaytimeTracker playtime;
    private GameInfoManager gameInfo;
    private boolean launchInProgress;
    private boolean ready;
    private long lastAxis;
    private String axisDirection = "";

    private static final int PICK_GAMES = 42;
    final Object fileLock = new Object();
    private final ExecutorService filesWorker = Executors.newSingleThreadExecutor();

    JSONArray storedFiles() {
        try { return new JSONArray(getSharedPreferences("library-v2", MODE_PRIVATE).getString("files", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
    private void storeFiles(JSONArray files) {
        getSharedPreferences("library-v2", MODE_PRIVATE).edit().putString("files", files.toString()).apply();
    }
    JSONObject findFile(int id) {
        synchronized (fileLock) {
            JSONArray files = storedFiles();
            for (int i=0; i<files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f != null && f.optInt("id") == id) return f;
            }
        }
        return null;
    }
    void notice(String text) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        });
    }
    String resolveGameSerial(JSONObject game){return covers.resolveForInfo(game);}
    void refreshCoverFiles() {
        runOnUiThread(()->{if(web!=null&&ready)web.evaluateJavascript("window.refreshCoverLibrary && window.refreshCoverLibrary();",null);});
    }
    private void refreshFiles() {
        runOnUiThread(() -> {
            if (web != null && ready) web.evaluateJavascript("window.refreshLibrary && window.refreshLibrary();", null);
        });
    }
    public final class LibraryBridge {
        @JavascriptInterface public String getLibrary() {
            synchronized (fileLock) {
                JSONArray result = new JSONArray();
                JSONArray files = storedFiles();
                for (int i=0; i<files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    try {
                        JSONObject visible = new JSONObject(f.toString());
                        visible.remove("uri");
                        covers.annotate(visible);
                        playtime.annotate(visible);
                        gameInfo.annotate(visible);
                        result.put(visible);
                    } catch (Exception ignored) {}
                }
                return result.toString();
            }
        }
        @JavascriptInterface public int themeVolume(){return music.volume();}
        @JavascriptInterface public void setThemeVolume(int value){runOnUiThread(()->music.setVolume(value));}
        @JavascriptInterface public boolean soundsEnabled(){return sounds.enabled();}
        @JavascriptInterface public void setSoundsEnabled(boolean value){runOnUiThread(()->sounds.setEnabled(value));}
        @JavascriptInterface public void uiSound(String key){runOnUiThread(()->sounds.play(key));}
        @JavascriptInterface public boolean themeEnabled(){return music.enabled();}
        @JavascriptInterface public void setThemeEnabled(boolean enabled){runOnUiThread(()->music.setEnabled(enabled));}
        @JavascriptInterface public boolean trackingAccess(){return playtime.hasAccess();}
        @JavascriptInterface public String trackingStatus(){return playtime.status();}
        @JavascriptInterface public void enableTracking(){runOnUiThread(()->playtime.openAccess());}
        @JavascriptInterface public boolean automaticInfo(){return gameInfo.automatic();}
        @JavascriptInterface public void setAutomaticInfo(boolean enabled){gameInfo.setAutomatic(enabled);}
        @JavascriptInterface public void downloadInfo(){gameInfo.schedule(true);}
        @JavascriptInterface public void openInfoSource(int id){String serial=covers.meta(id).optString("serial");if(serial.matches("[A-Z]{4}-[0-9]{5}"))runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/niemasd/GameDB-PS2/tree/main/games/"+serial)));}catch(Exception e){notice("No browser is available.");}});}
        @JavascriptInterface public boolean automaticCovers(){return covers.automatic();}
        @JavascriptInterface public void setAutomaticCovers(boolean enabled){covers.setAutomatic(enabled);}
        @JavascriptInterface public void downloadCovers(){covers.schedule(true);}
        @JavascriptInterface public void chooseCover(int id){covers.choose(id);}
        @JavascriptInterface public void chooseGames() { runOnUiThread(() -> pickGames()); }
        @JavascriptInterface public void play(int id) { filesWorker.execute(() -> launchFile(id)); }
        @JavascriptInterface public boolean biosReady() { return SessionProvider.biosReady(MainActivity.this); }
        @JavascriptInterface public void openBios() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(MainActivity.this,com.armsx2.Main.class).putExtra("blackIceBiosSetup",true)); }
                catch(Exception e) { notice("BIOS setup could not open. Please try again."); }
            });
        }
        @JavascriptInterface public void openEmulator() { runOnUiThread(() -> openCoreSetup()); }
        @JavascriptInterface public void remove(int id) {
            filesWorker.execute(() -> {
                synchronized (fileLock) {
                    JSONArray files = storedFiles(), keep = new JSONArray();
                    for (int i=0; i<files.length(); i++) {
                        JSONObject f=files.optJSONObject(i);
                        if (f == null) continue;
                        if (f.optInt("id") != id) keep.put(f);
                        else try { getContentResolver().releasePersistableUriPermission(Uri.parse(f.getString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                             catch (Exception ignored) {}
                    }
                    storeFiles(keep);
                    covers.forget(id);
                }
                refreshFiles();
                notice("Removed from library. The original file was not deleted.");
            });
        }
    }
    private void pickGames() {
        if (isFinishing()) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker, PICK_GAMES); }
        catch (Exception e) { notice("Android's file picker is unavailable on this device."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_GAMES || result != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i=0; i<clip.getItemCount(); i++) {
                Uri uri=clip.getItemAt(i).getUri();
                if (uri != null && !uris.contains(uri)) uris.add(uri);
            }
        } else if (data.getData() != null) uris.add(data.getData());
        notice("Adding selected files…");
        filesWorker.execute(() -> importFiles(uris));
    }
    private void importFiles(ArrayList<Uri> uris) {
        int added=0, duplicates=0, unsupported=0, failed=0;
        synchronized (fileLock) {
            JSONArray files = storedFiles();
            int nextId=getSharedPreferences("library-v2",MODE_PRIVATE).getInt("nextId",1);
            for (Uri uri: uris) {
                if (!"content".equals(uri.getScheme())) { failed++; continue; }
                String filename=null;
                long size=-1;
                try (Cursor cursor=getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (n>=0) filename=cursor.getString(n);
                        if (s>=0 && !cursor.isNull(s)) size=cursor.getLong(s);
                    }
                } catch (Exception e) { failed++; continue; }
                if (filename == null) { failed++; continue; }
                String lower=filename.toLowerCase(Locale.ROOT);
                if (!lower.matches(".*\\.(iso|bin|chd|cso|img|mdf|gz|elf)$")) { unsupported++; continue; }
                JSONObject existing=null;
                for (int i=0;i<files.length();i++) {
                    JSONObject item=files.optJSONObject(i);
                    if (item!=null && uri.toString().equals(item.optString("uri"))) {existing=item;break;}
                }
                if (existing!=null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        existing.put("filename",filename);existing.put("size",size);
                        duplicates++;
                    } catch (Exception e) {failed++;}
                    continue;
                }
                try {
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try (ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"r")) {
                        if (fd == null) throw new IllegalStateException("Unreadable file");
                    }
                    JSONObject f=new JSONObject();
                    f.put("id",nextId++);f.put("uri",uri.toString());f.put("filename",filename);
                    f.put("title",filename.replaceFirst("(?i)\\.(iso|bin|chd|cso|img|mdf|gz|elf)$", "").replace('_',' '));
                    f.put("size",size);f.put("added",System.currentTimeMillis());f.put("lastLaunch",0);
                    files.put(f);added++;
                } catch (Exception e) { failed++; }
            }
            storeFiles(files);
            getSharedPreferences("library-v2",MODE_PRIVATE).edit().putInt("nextId",nextId).apply();
        }
        refreshFiles();
        String message=added+" file(s) added";
        if (duplicates>0) message+=". "+duplicates+" already in your library";
        if (unsupported>0) message+=". Select ISO/BIN/CHD/CSO/IMG/MDF/GZ/ELF files; extract ZIP or 7Z archives first";
        if (failed>0) message+=". "+failed+" file(s) could not be accessed; try local device storage";
        notice(message);
        covers.schedule(false);
        gameInfo.schedule(false);
    }
    private void openCoreSetup() {
        try {startActivity(new Intent(this,com.armsx2.Main.class));}
        catch(Exception e){notice("The built-in emulator could not open: "+e.getMessage());}
    }
    private void launchFile(int id) {
        JSONObject f=findFile(id);
        if (f==null) {notice("Choose a file from your library first.");return;}
        Uri uri=Uri.parse(f.optString("uri"));
        try (ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"r")) {
            if (fd==null) throw new IllegalStateException("Unavailable");
        } catch (Exception e) {notice("This file is no longer accessible. Add it again from its current location.");return;}
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || launchInProgress) return;
            Intent launch=new Intent(this,com.armsx2.Main.class);
            launch.setAction(Intent.ACTION_VIEW);
            launch.setData(uri);
            launch.setClipData(ClipData.newRawUri("Selected game",uri));
            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                launchInProgress=true;
                playtime.begin(id);
                startActivity(launch);
                filesWorker.execute(() -> {
                    synchronized (fileLock) {
                        JSONArray files=storedFiles();
                        for (int i=0;i<files.length();i++) {
                            JSONObject item=files.optJSONObject(i);
                            if (item!=null && item.optInt("id")==id) try {item.put("lastLaunch",System.currentTimeMillis());}catch(Exception ignored){}
                        }
                        storeFiles(files);
                    }
                    refreshFiles();
                });
            } catch (Exception e) {launchInProgress=false;playtime.cancel();notice("The built-in emulator could not open this file. Complete Emulator setup, then retry.");}
        });
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        covers=new CoverManager(this);
        music=new ThemeMusic(this);
        sounds=new UiSounds(this);
        playtime=new PlaytimeTracker(this);
        gameInfo=new GameInfoManager(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            getWindow().setAttributes(attributes);
        }
        web = new WebView(this);
        web.setBackgroundColor(0xff080b10);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return covers.intercept(request);}
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // This offline app has no external navigation.
            }
            @Override public void onPageFinished(WebView view, String url) {
                ready = true;
                covers.schedule(false);
                gameInfo.schedule(false);
                immersive();
            }
        });
        web.addJavascriptInterface(new LibraryBridge(), "AndroidLibrary");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new OnBackInvokedCallback() {
                    @Override public void onBackInvoked() { handleBack(); }
                });
        }
        immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    private void control(String action) {
        if (ready) web.evaluateJavascript("window.androidControl && window.androidControl('" + action + "');", null);
    }

    private void handleBack() {
        if (!ready) { finish(); return; }
        web.evaluateJavascript("(function(){if(modal || page !== 'home'){back();return true;}return false;})()", value -> {
            if ("false".equals(value) && !isFinishing()) {
                new AlertDialog.Builder(this).setTitle("Close Black Ice?")
                    .setMessage("Your selected files and favorites are saved on this device.")
                    .setNegativeButton("Stay", null)
                    .setPositiveButton("Close", (dialog, which) -> finish()).show();
            }
        });
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        String action = null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: action = "select"; break;
            case KeyEvent.KEYCODE_BUTTON_B: action = "back"; break;
            case KeyEvent.KEYCODE_BUTTON_X: action = "details"; break;
            case KeyEvent.KEYCODE_BUTTON_Y: action = "context"; break;
            case KeyEvent.KEYCODE_BUTTON_L1: action = "home"; break;
            case KeyEvent.KEYCODE_BUTTON_R1: action = "library"; break;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU: action = "menu"; break;
            default: break;
        }
        boolean gamepad = (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (gamepad) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: action = "up"; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: action = "down"; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: action = "left"; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: action = "right"; break;
                default: break;
            }
        }
        if (action != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN &&
                (event.getRepeatCount() == 0 || action.equals("up") || action.equals("down") || action.equals("left") || action.equals("right"))) control(action);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hx) > .5f) x = hx;
            if (Math.abs(hy) > .5f) y = hy;
            String direction = Math.abs(x) > Math.abs(y) && Math.abs(x) > .55f ? (x < 0 ? "left" : "right")
                : Math.abs(y) > .55f ? (y < 0 ? "up" : "down") : "";
            long now = SystemClock.uptimeMillis();
            if (!direction.isEmpty() && (!direction.equals(axisDirection) || now - lastAxis >= 190)) {
                control(direction);
                lastAxis = now;
            }
            axisDirection = direction;
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override protected void onPause() { if(music!=null)music.foreground(false);if(sounds!=null)sounds.foreground(false);if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume();launchInProgress=false;if (web != null) web.onResume();if(music!=null)music.foreground(true);if(sounds!=null)sounds.foreground(true);if(playtime!=null)filesWorker.execute(()->{playtime.finish();refreshFiles();});immersive(); }
    @Override protected void onDestroy() {
        ready = false;
        filesWorker.shutdownNow();
        if(covers!=null)covers.close();
        if(music!=null)music.close();
        if(sounds!=null)sounds.close();
        if(gameInfo!=null)gameInfo.close();
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
}

package dev.aether.preview;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Serial-based xlenore downloads. Game contents are never sent to the server. */
final class CoverManager {
    private static final String BASE="https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/";
    private final MainActivity app;
    private final SharedPreferences prefs;
    private final File directory;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private JSONArray titleIndex;
    private final Map<String,List<JSONObject>> titleLookup=new HashMap<>();
    private boolean busy,pending,forcePending,closed;
    CoverManager(MainActivity app){this.app=app;prefs=app.getSharedPreferences("covers-v3",0);directory=new File(app.getFilesDir(),"covers");directory.mkdirs();}
    boolean automatic(){return prefs.getBoolean("automatic",true);}
    void setAutomatic(boolean value){prefs.edit().putBoolean("automatic",value).apply();if(value)schedule(false);}
    JSONObject meta(int id){try{return new JSONObject(prefs.getString("game-"+id,"{}"));}catch(Exception e){return new JSONObject();}}
    private void save(int id,JSONObject value){prefs.edit().putString("game-"+id,value.toString()).apply();}
    void forget(int id){prefs.edit().remove("game-"+id).apply();}
    private File file(String serial){return new File(directory,serial+".jpg");}
    private boolean valid(String serial){return serial.matches("[A-Z]{4}-[0-9]{5}");}
    void annotate(JSONObject game){
        try {JSONObject m=meta(game.getInt("id"));String serial=m.optString("serial");boolean has=valid(serial)&&file(serial).isFile();
            game.put("coverSerial",serial);game.put("coverState",has?"Downloaded":m.optString("status","Waiting for cover"));
            game.put("coverAvailable",has);game.put("coverUrl",has?"https://covers.aether.local/"+serial+".jpg?v="+file(serial).lastModified():"");
        }catch(Exception ignored){}
    }
    WebResourceResponse intercept(WebResourceRequest request){
        Uri uri=request.getUrl();
        if("https".equals(uri.getScheme())&&"covers.aether.local".equals(uri.getHost())){
            String path=uri.getPath();
            if(path!=null&&path.matches("/[A-Z]{4}-[0-9]{5}\\.jpg"))try {
                return new WebResourceResponse("image/jpeg",null,new FileInputStream(file(path.substring(1,path.length()-4))));
            }catch(IOException ignored){}
            return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
        }
        if("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))
            return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
        return null;
    }
    synchronized void schedule(boolean force){
        if(closed||(!force&&!automatic()))return;
        pending=true;forcePending|=force;
        if(busy)return;busy=true;
        worker.execute(()->{while(true){boolean retry;synchronized(this){if(closed||!pending){busy=false;return;}pending=false;retry=forcePending;forcePending=false;}try{downloadPass(retry);}catch(Exception e){app.notice("Cover download could not finish. Retry from Settings.");}}});
    }
    synchronized void close(){closed=true;worker.shutdownNow();}
    private synchronized JSONArray titles()throws Exception{
        if(titleIndex==null)try(InputStream input=app.getAssets().open("cover-titles.json")){
            titleIndex=new JSONArray(new String(readAll(input,4*1024*1024),"UTF-8"));
            for(int i=0;i<titleIndex.length();i++){JSONObject row=titleIndex.getJSONObject(i);String key=CoverSerial.normalizeTitle(row.getString("title"));List<JSONObject> entries=titleLookup.get(key);if(entries==null){entries=new ArrayList<>();titleLookup.put(key,entries);}entries.add(row);}
        }
        return titleIndex;
    }
    private synchronized List<JSONObject> candidates(JSONObject game)throws Exception{
        String title=CoverSerial.normalizeTitle(game.optString("filename"));String name=game.optString("filename").toLowerCase(Locale.ROOT);
        String region=name.matches(".*\\b(usa|ntsc-u)\\b.*")?"NTSC-U":name.matches(".*\\b(europe|pal)\\b.*")?"PAL":name.matches(".*\\b(japan|ntsc-j)\\b.*")?"NTSC-J":"";
        List<JSONObject> result=new ArrayList<>();titles();List<JSONObject> entries=titleLookup.get(title);
        if(entries!=null)for(JSONObject row:entries)if(region.isEmpty()||region.equals(row.optString("region")))result.add(row);
        return result;
    }
    private synchronized String detect(JSONObject game,JSONObject state)throws Exception{
        String serial=state.optString("serial");if(valid(serial))return serial;
        String name=game.optString("filename");
        if(name.toLowerCase(Locale.ROOT).matches(".*\\.(iso|bin|img|mdf)$")) {
            try(ParcelFileDescriptor fd=app.getContentResolver().openFileDescriptor(Uri.parse(game.getString("uri")),"r")){
                if(fd!=null)try(FileInputStream input=new FileInputStream(fd.getFileDescriptor())){
                    FileChannel channel=input.getChannel();
                    serial=CoverSerial.fromDisc((offset,length)->{ByteBuffer bytes=ByteBuffer.allocate(length);channel.position(offset);while(bytes.hasRemaining()){int count=channel.read(bytes);if(count<=0)throw new IOException("Short file");}return bytes.array();});
                }
            }catch(Exception ignored){}
            if(valid(serial)){state.put("source","Disc SYSTEM.CNF");return serial;}
        }
        serial=CoverSerial.fromText(name);if(valid(serial)){state.put("source","Filename");return serial;}
        List<JSONObject> matches=candidates(game);
        if(matches.size()==1){state.put("source","Exact title match");return matches.get(0).getString("serial");}
        return "";
    }
    synchronized String resolveForInfo(JSONObject game){
        try{int id=game.getInt("id");JSONObject state=meta(id);String serial=detect(game,state);if(valid(serial)){state.put("serial",serial);save(id,state);}return serial;}catch(Exception e){return "";}
    }
    private void downloadPass(boolean force)throws Exception{
        JSONArray list;synchronized(app.fileLock){list=app.storedFiles();}
        int downloaded=0,missing=0,failed=0;long now=System.currentTimeMillis();
        for(int i=0;i<list.length();i++){
            synchronized(this){if(closed||(!force&&!automatic()))break;}
            JSONObject game=list.optJSONObject(i);if(game==null)continue;int id=game.getInt("id");JSONObject state=meta(id);String serial=state.optString("serial");
            if(valid(serial)&&file(serial).isFile())continue;
            long cooldown="Download failed — retry".equals(state.optString("status"))?30*60*1000:24*60*60*1000;
            String status=state.optString("status");boolean throttled=status.equals("Cover not found")||status.equals("Choose a cover match")||status.equals("Download failed — retry");
            if(throttled&&!force&&now-state.optLong("attempt")<cooldown)continue;
            serial=detect(game,state);state.put("serial",serial);state.put("attempt",now);
            if(!valid(serial)){state.put("status","Choose a cover match");save(id,state);missing++;app.refreshCoverFiles();continue;}
            state.put("status","Downloading cover…");save(id,state);app.refreshCoverFiles();
            try{if(fetch(serial)){state.put("status","Downloaded");downloaded++;}else{state.put("status","Cover not found");missing++;}}
            catch(Exception e){state.put("status","Download failed — retry");failed++;}
            if(app.findFile(id)!=null)save(id,state);app.refreshCoverFiles();
        }
        if(force||downloaded>0)app.notice(downloaded+" cover(s) downloaded"+(missing>0?" · "+missing+" need a match or have no cover":"")+(failed>0?" · "+failed+" failed; check your connection":""));
    }
    private static byte[] readAll(InputStream input,int max)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1){if(output.size()+count>max)throw new IOException("Response too large");output.write(buffer,0,count);}return output.toByteArray();
    }
    private boolean fetch(String serial)throws Exception{
        if(file(serial).isFile())return true;
        HttpsURLConnection connection=(HttpsURLConnection)new URL(BASE+serial+".jpg").openConnection();
        connection.setConnectTimeout(12000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","BlackIceLauncher/3.1");
        byte[] data;
        try{int status=connection.getResponseCode();if(status==404)return false;if(status!=200)throw new IOException("HTTP "+status);try(InputStream stream=connection.getInputStream()){data=readAll(stream,6*1024*1024);}}finally{connection.disconnect();}
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(options.outWidth<=0||options.outHeight<=0||options.outWidth>8000||options.outHeight>8000)throw new IOException("Invalid cover dimensions");
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        while(options.outWidth/options.inSampleSize>1024||options.outHeight/options.inSampleSize>1536)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);if(bitmap==null)throw new IOException("Invalid cover");
        AtomicFile target=new AtomicFile(file(serial));FileOutputStream output=null;
        try{output=target.startWrite();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,92,output))throw new IOException("Encoding failed");target.finishWrite(output);output=null;}
        finally{if(output!=null)target.failWrite(output);bitmap.recycle();}
        return true;
    }
    void choose(int id){
        synchronized(this){if(closed)return;}
        worker.execute(()->{JSONObject game=app.findFile(id);if(game==null)return;List<JSONObject> found;
            try{found=candidates(game);}catch(Exception e){found=new ArrayList<>();}
            final List<JSONObject> matches=found;app.runOnUiThread(()->{
                if(app.isFinishing()||app.isDestroyed())return;
                if(matches.isEmpty()){enterSerial(id);return;}
                List<String> labels=new ArrayList<>();for(JSONObject row:matches)labels.add(row.optString("title")+" · "+row.optString("region")+" · "+row.optString("serial"));labels.add("Enter a different serial…");
                new AlertDialog.Builder(app).setTitle("Choose this game's cover").setItems(labels.toArray(new String[0]),(dialog,index)->{if(index==matches.size())enterSerial(id);else setSerial(id,matches.get(index).optString("serial"));}).setNegativeButton("Cancel",null).show();
            });
        });
    }
    private void enterSerial(int id){
        EditText input=new EditText(app);input.setSingleLine(true);input.setHint("SLUS-20915");input.setText(meta(id).optString("serial"));input.setPadding(32,24,32,24);
        AlertDialog dialog=new AlertDialog.Builder(app).setTitle("Game serial for cover").setMessage("Use the serial printed on the disc or shown in AetherSX2's game properties. Example: SLUS-20915.").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Download",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{String serial=CoverSerial.fromText(input.getText().toString().trim());if(!valid(serial)){input.setError("Enter a serial such as SLUS-20915");return;}dialog.dismiss();setSerial(id,serial);}));dialog.show();
    }
    private void setSerial(int id,String serial){
        worker.execute(()->{if(app.findFile(id)==null)return;try{JSONObject state=new JSONObject();state.put("serial",serial);state.put("source","Selected by user");state.put("attempt",0);state.put("status","Waiting for cover");save(id,state);app.refreshCoverFiles();schedule(true);}catch(Exception e){app.notice("Could not save this cover match.");}});
    }
}

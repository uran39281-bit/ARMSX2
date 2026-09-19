package dev.aether.preview;

import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Downloads and caches the GameDB-PS2 structured release; matches by serial. */
final class GameInfoManager {
    private static final String SOURCE="https://github.com/niemasd/GameDB-PS2/releases/latest/download/PS2.data.json";
    private final MainActivity app;
    private final SharedPreferences prefs;
    private final AtomicFile cache;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile JSONObject database;
    private volatile String state="Game info is loading…";
    private boolean busy,pending,forcePending,closed;
    GameInfoManager(MainActivity app){this.app=app;prefs=app.getSharedPreferences("black-ice-gameinfo",0);cache=new AtomicFile(new File(app.getFilesDir(),"gamedb-ps2.json"));}
    boolean automatic(){return prefs.getBoolean("automatic",true);}
    void setAutomatic(boolean value){prefs.edit().putBoolean("automatic",value).apply();if(value)schedule(false);}
    static String text(Object value){
        if(value==null||value==JSONObject.NULL)return "";
        String result;
        if(value instanceof JSONArray){StringBuilder b=new StringBuilder();JSONArray list=(JSONArray)value;for(int i=0;i<list.length();i++){String part=text(list.opt(i));if(!part.isEmpty()){if(b.length()>0)b.append(", ");b.append(part);}}result=b.toString();}
        else if(value instanceof String||value instanceof Number)result=String.valueOf(value);else return "";
        return result.length()>2000?result.substring(0,2000):result.trim();
    }
    void annotate(JSONObject game){
        try{String serial=game.optString("coverSerial");JSONObject db=database,row=db==null?null:db.optJSONObject(serial);
            game.put("infoStatus",serial.isEmpty()?"Match this game's serial to find its info":row==null?(db==null?state:"No GameDB-PS2 record for this serial"):"GameDB-PS2 · factual metadata summary");
            game.put("description","");game.put("gameDeveloper","");game.put("gamePublisher","");game.put("gameGenre","");game.put("gameRelease","");game.put("gameRegion","");
            if(row!=null){String title=text(row.opt("title")),developer=text(row.opt("developer")),publisher=text(row.opt("publisher")),release=text(row.opt("release_date")),region=text(row.opt("region"));
                game.put("gameDeveloper",developer);game.put("gamePublisher",publisher);game.put("gameGenre",text(row.opt("genre")));game.put("gameRelease",release);game.put("gameRegion",region);
                String description=text(row.opt("description"));if(description.isEmpty())description=text(row.opt("overview"));
                if(description.isEmpty())description=GameInfoText.summary(title,developer,publisher,release,region);
                game.put("description",description);
            }
        }catch(Exception ignored){}
    }
    synchronized void schedule(boolean force){
        if(closed)return;pending=true;forcePending|=force;if(busy)return;busy=true;
        worker.execute(()->{while(true){boolean retry;synchronized(this){if(closed||!pending){busy=false;return;}pending=false;retry=forcePending;forcePending=false;}try{runPass(retry);}catch(Exception e){state="Game info download failed — retry in Settings";app.refreshCoverFiles();}}});
    }
    private void runPass(boolean force)throws Exception{
        if(database==null)try(InputStream input=cache.openRead()){database=new JSONObject(new String(read(input,12*1024*1024),"UTF-8"));state="Cached game info";}catch(Exception ignored){}
        JSONArray games;synchronized(app.fileLock){games=app.storedFiles();}
        if(games.length()==0){app.refreshCoverFiles();return;}
        boolean allowed=force||automatic();long now=System.currentTimeMillis();
        if(allowed&&(database==null||force||now-prefs.getLong("checked",0)>7L*24*60*60*1000)&&(force||now-prefs.getLong("attempt",0)>30*60*1000)){
            prefs.edit().putLong("attempt",now).apply();state="Downloading game info…";app.refreshCoverFiles();
            try {
                byte[] bytes=download();JSONObject parsed=new JSONObject(new String(bytes,"UTF-8"));if(parsed.length()==0)throw new IOException("Empty database");
                FileOutputStream output=null;try{output=cache.startWrite();output.write(bytes);cache.finishWrite(output);output=null;}finally{if(output!=null)cache.failWrite(output);}
                database=parsed;prefs.edit().putLong("checked",now).apply();state="Game info downloaded";
                if(force)app.notice("GameDB-PS2 information updated.");
            }catch(Exception e){state=database==null?"Game info download failed — retry in Settings":"Using cached game information";if(force)app.notice("Could not refresh GameDB-PS2. Cached information is kept.");}
        }
        if(database!=null)for(int i=0;i<games.length();i++){synchronized(this){if(closed)return;}JSONObject game=games.optJSONObject(i);if(game!=null)app.resolveGameSerial(game);}
        if(database==null&&!automatic())state="Automatic game info is off";
        app.refreshCoverFiles();
    }
    private static byte[] read(InputStream input,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Database exceeds size limit");out.write(b,0,n);}return out.toByteArray();}
    private static boolean allowedHost(String host){return host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com");}
    private byte[] download()throws Exception{
        URL url=new URL(SOURCE);
        for(int redirect=0;redirect<5;redirect++){
            if(!url.getProtocol().equals("https")||!allowedHost(url.getHost().toLowerCase(Locale.ROOT)))throw new IOException("Unexpected download destination");
            HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","BlackIceLauncher/3.1");
            try{int code=connection.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Missing redirect");url=new URL(url,location);continue;}
                if(code!=200)throw new IOException("HTTP "+code);try(InputStream input=connection.getInputStream()){return read(input,12*1024*1024);}
            }finally{connection.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    synchronized void close(){closed=true;worker.shutdownNow();}
}

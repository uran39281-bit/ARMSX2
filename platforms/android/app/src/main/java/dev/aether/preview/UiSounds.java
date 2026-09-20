package dev.aether.preview;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.HashSet;
final class UiSounds {
 private final SharedPreferences prefs;
 private final SoundPool pool;
 private final HashMap<String,Integer> ids=new HashMap<>();
 private final HashSet<Integer> ready=new HashSet<>();
 private boolean foreground;
 private long last;
 UiSounds(MainActivity app){
  prefs=app.getSharedPreferences("black-ice-audio",0);
  pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
  pool.setOnLoadCompleteListener((p,id,status)->{if(status==0)ready.add(id);});
  for(String key:new String[]{"click","back","success","move","horizontal","boundary","slider","off","error"})try(AssetFileDescriptor f=app.getAssets().openFd("sounds/"+key+".ogg")){ids.put(key,pool.load(f,1));}catch(Exception ignored){}
 }
 boolean enabled(){return prefs.getBoolean("effects",true);}
 void setEnabled(boolean value){prefs.edit().putBoolean("effects",value).apply();if(!value)pool.autoPause();}
 void foreground(boolean value){foreground=value;if(!value)pool.autoPause();}
 void play(String key){Integer id=ids.get(key);long now=SystemClock.elapsedRealtime();if(!foreground||!enabled()||id==null||!ready.contains(id)||now-last<85)return;last=now;pool.play(id,.18f,.18f,1,0,1f);}
 void close(){foreground=false;pool.release();}
}

package dev.aether.preview;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
/** Private IPC endpoint: emulation runs in its own process so its native teardown cannot kill Home. */
public final class SessionProvider extends ContentProvider {
 private static long runningSince;
 private static SharedPreferences prefs(Context c){return c.getSharedPreferences("black-ice-playtime",0);}
 static synchronized void begin(Context c,int id){runningSince=0;prefs(c).edit().putInt("activeId",id).putLong("last-"+id,0).putBoolean("counted",false).commit();}
 static synchronized void clear(Context c){runningSince=0;prefs(c).edit().remove("activeId").remove("started").commit();}
 @Override public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  if(Binder.getCallingUid()!=Process.myUid())throw new SecurityException("Private session endpoint");
  synchronized(SessionProvider.class){
   SharedPreferences p=prefs(getContext());int id=p.getInt("activeId",-1);if(id<0)return Bundle.EMPTY;
   long now=SystemClock.elapsedRealtime();
   if("running".equals(method)){if(runningSince==0)runningSince=now;}
   else if("paused".equals(method)||"checkpoint".equals(method)){
    if(runningSince>0){long elapsed=Math.max(0,now-runningSince);SharedPreferences.Editor e=p.edit().putLong("total-"+id,p.getLong("total-"+id,0)+elapsed).putLong("last-"+id,p.getLong("last-"+id,0)+elapsed);
     if(elapsed>0&&!p.getBoolean("counted",false))e.putInt("sessions-"+id,p.getInt("sessions-"+id,0)+1).putBoolean("counted",true);e.commit();}
    runningSince="checkpoint".equals(method)&&runningSince>0?now:0;
   }
  }return Bundle.EMPTY;
 }
 @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
 @Override public String getType(Uri u){return null;}
 @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}

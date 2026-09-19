package dev.aether.preview;
import android.content.SharedPreferences;
import org.json.JSONObject;
/** Totals are supplied by the in-app VM-state observer, without Usage Access. */
final class PlaytimeTracker {
 private final MainActivity app;
 private final SharedPreferences prefs;
 PlaytimeTracker(MainActivity app){this.app=app;prefs=app.getSharedPreferences("black-ice-playtime",0);}
 boolean hasAccess(){return true;}
 synchronized void begin(int id){SessionProvider.begin(app,id);}
 synchronized void cancel(){SessionProvider.clear(app);}
 synchronized void finish(){SessionProvider.clear(app);}
 void annotate(JSONObject game){try{int id=game.getInt("id");game.put("playMs",prefs.getLong("total-"+id,0));game.put("lastSessionMs",prefs.getLong("last-"+id,0));game.put("sessions",prefs.getInt("sessions-"+id,0));}catch(Exception ignored){}}
 String status(){return "Built-in VM tracking is active; paused and background time are excluded";}
 void openAccess(){app.notice("Playtime is tracked by the built-in emulator. No Usage Access permission is needed.");}
}

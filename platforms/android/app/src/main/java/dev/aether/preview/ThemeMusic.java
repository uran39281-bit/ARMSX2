package dev.aether.preview;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

/** Foreground-only playback of the user-supplied Black Ice Theme. */
final class ThemeMusic {
    private final MainActivity app;
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private MediaPlayer player;
    private boolean foreground,prepared,hasFocus,failed;
    ThemeMusic(MainActivity app){
        this.app=app;prefs=app.getSharedPreferences("black-ice-audio",0);audio=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{
            hasFocus=change==AudioManager.AUDIOFOCUS_GAIN;
            if(hasFocus)startIfReady();else pausePlayer();
        }).build();
    }
    int volume(){return prefs.getInt("volume",10);}
    void setVolume(int value){int v=Math.max(0,Math.min(100,value));prefs.edit().putInt("volume",v).apply();if(player!=null)player.setVolume(v/100f,v/100f);}
    boolean enabled(){return prefs.getBoolean("enabled",true);}
    void setEnabled(boolean enabled){prefs.edit().putBoolean("enabled",enabled).apply();if(enabled){failed=false;if(foreground)resume();}else pause();}
    void foreground(boolean visible){foreground=visible;if(visible)resume();else pause();}
    private void resume(){
        if(!foreground||!enabled()||failed)return;
        if(!hasFocus)hasFocus=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if(player==null){
            try(AssetFileDescriptor asset=app.getAssets().openFd("black-ice-theme.mp3")){
                player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                player.setDataSource(asset.getFileDescriptor(),asset.getStartOffset(),asset.getLength());player.setLooping(true);player.setVolume(volume()/100f,volume()/100f);
                player.setOnPreparedListener(media->{prepared=true;startIfReady();});
                player.setOnErrorListener((media,what,extra)->{fail();return true;});player.prepareAsync();
            }catch(Exception e){fail();}
        }else startIfReady();
    }
    private void startIfReady(){if(foreground&&enabled()&&hasFocus&&prepared&&player!=null)try{player.start();}catch(Exception e){fail();}}
    private void pausePlayer(){if(player!=null&&prepared)try{if(player.isPlaying())player.pause();}catch(Exception ignored){}}
    private void pause(){pausePlayer();audio.abandonAudioFocusRequest(focus);hasFocus=false;}
    private void fail(){failed=true;prepared=false;if(player!=null){player.release();player=null;}audio.abandonAudioFocusRequest(focus);hasFocus=false;app.notice("Black Ice Theme could not play. Try switching it off and on.");}
    void close(){foreground=false;pause();if(player!=null){player.release();player=null;}prepared=false;}
}

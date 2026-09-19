package dev.aether.preview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded ISO9660 SYSTEM.CNF reader; no compressed-image decoding or game upload. */
public final class CoverSerial {
    private static final Pattern SERIAL=Pattern.compile("(?i)(?<![A-Z0-9])([A-Z]{4})[-_ ]([0-9]{3})[. _-]?([0-9]{2})(?![0-9])");
    public interface Reader { byte[] read(long offset,int length) throws IOException; }
    public static String fromText(String text) {
        Matcher m=SERIAL.matcher(text==null?"":text);String serial="";
        while(m.find()) {String v=m.group(1).toUpperCase(Locale.ROOT)+"-"+m.group(2)+m.group(3);if(!serial.isEmpty()&&!serial.equals(v))return "";serial=v;}
        return serial;
    }
    public static String normalizeTitle(String text) {
        String v=text.replaceFirst("(?i)\\.(iso|bin|chd|cso|img|mdf|gz|elf)$","").replaceAll("\\([^)]*\\)|\\[[^]]*\\]", " ");
        v=Normalizer.normalize(v,Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        if(v.matches(".*,[ ]*the[ ]*$"))v="the "+v.replaceFirst(",[ ]*the[ ]*$","");
        v=v.replaceAll("\\biii\\b","3").replaceAll("\\bii\\b","2").replaceAll("\\biv\\b","4");
        return v.replaceAll("[^a-z0-9]+", " ").trim();
    }
    private static long le(byte[] b,int p){return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24);}
    private static byte[] logical(Reader r,long offset,int length,int sector,int prefix)throws IOException{
        byte[] result=new byte[length];int done=0;
        while(done<length){long position=offset+done;int part=(int)(position%2048);int n=Math.min(length-done,2048-part);
            byte[] bytes=r.read((position/2048)*sector+prefix+part,n);if(bytes.length!=n)throw new IOException("Short read");System.arraycopy(bytes,0,result,done,n);done+=n;}
        return result;
    }
    public static String fromDisc(Reader r){
        for(int[] layout:new int[][]{{2048,0},{2352,16},{2352,24}}){
            try {
                byte[] pvd=logical(r,16L*2048,2048,layout[0],layout[1]);
                if(pvd[0]!=1||!new String(pvd,1,5,StandardCharsets.US_ASCII).equals("CD001"))continue;
                long root=le(pvd,158),size=le(pvd,166);
                if(size<=0||size>2*1024*1024||root>0x3fffffffL)continue;
                byte[] dir=logical(r,root*2048,(int)size,layout[0],layout[1]);
                for(int p=0;p<dir.length;){int length=dir[p]&255;if(length==0){p=((p/2048)+1)*2048;continue;}
                    if(length<34||p+length>dir.length)break;int namesize=dir[p+32]&255;
                    if(33+namesize<=length){String name=new String(dir,p+33,namesize,StandardCharsets.US_ASCII);
                        if(name.equalsIgnoreCase("SYSTEM.CNF;1")||name.equalsIgnoreCase("SYSTEM.CNF")){
                            long block=le(dir,p+2),bytes=le(dir,p+10);if(bytes<1||bytes>65536)break;
                            String config=new String(logical(r,block*2048,(int)bytes,layout[0],layout[1]),StandardCharsets.ISO_8859_1);
                            for(String line:config.split("[\\r\\n]+"))if(line.trim().matches("(?i)^BOOT2\\s*=.*"))return fromText(line);
                        }}p+=length;
                }
            }catch(IOException|RuntimeException ignored){}
        }
        return "";
    }
}

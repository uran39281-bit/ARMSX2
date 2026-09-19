package dev.aether.preview;

/** Deterministic factual summary, not an invented plot synopsis. */
public final class GameInfoText {
    public static String summary(String title,String developer,String publisher,String release,String region){
        if(title==null||title.trim().isEmpty())return "";
        StringBuilder text=new StringBuilder(title.trim()).append(" is a PlayStation 2 game.");
        if(!developer.isEmpty())text.append(" Developed by ").append(developer).append('.');
        if(!publisher.isEmpty())text.append(" Published by ").append(publisher).append('.');
        if(!release.isEmpty()){text.append(" This release is dated ").append(release);if(!region.isEmpty())text.append(" (").append(region).append(')');text.append('.');}
        return text.toString();
    }
}

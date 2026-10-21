package android.net;
public class Uri { private final String s; private Uri(String s){this.s=s;}
  public static Uri parse(String s){ return new Uri(s); }
  public String getScheme(){ int i=s.indexOf(':'); return i<0?null:s.substring(0,i); }
  public String getPath(){ int i=s.indexOf(':', s.indexOf("//")+2); return i<0?s:s.substring(i+1); }
  public String toString(){ return s; } }

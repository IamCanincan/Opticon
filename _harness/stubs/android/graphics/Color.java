package android.graphics;
public final class Color {
  public static final int WHITE = 0xFFFFFFFF;
  public static int alpha(int c){ return (c>>>24)&0xff; }
  public static int red(int c){ return (c>>>16)&0xff; }
  public static int green(int c){ return (c>>>8)&0xff; }
  public static int blue(int c){ return c&0xff; }
  public static int argb(int a,int r,int g,int b){ return (a<<24)|(r<<16)|(g<<8)|b; }
}

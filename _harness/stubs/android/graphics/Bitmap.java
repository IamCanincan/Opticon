package android.graphics;
import java.util.Arrays;
/** 只给离线判据台用的最小实现：真实像素数组 + 与 Android 一致的 rowBytes 补齐规则。 */
public final class Bitmap {
  public enum Config { ALPHA_8, ARGB_8888, RGB_565 }
  public final int width, height; public final Config config; public int[] px;
  private Bitmap(int w, int h, Config c) { width=w; height=h; config=c; px=new int[w*h]; }
  public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(w,h,c); }
  public static Bitmap createBitmap(Bitmap s, int x, int y, int w, int h) {
    Bitmap o = new Bitmap(w,h,s.config);
    for (int j=0;j<h;j++) System.arraycopy(s.px,(y+j)*s.width+x, o.px, j*w, w);
    return o;
  }
  public static Bitmap createScaledBitmap(Bitmap s, int w, int h, boolean filter) {
    Bitmap o = new Bitmap(w,h,s.config);
    for (int j=0;j<h;j++) for (int i=0;i<w;i++) {
      int sx=Math.min(s.width-1, i*s.width/w), sy=Math.min(s.height-1, j*s.height/h);
      o.px[j*w+i]=s.px[sy*s.width+sx];
    }
    return o;
  }
  public int getWidth(){return width;} public int getHeight(){return height;}
  /** ALPHA_8 每像素 1 字节但 rowBytes 补到 4 的倍数 —— 与平台一致，判据台靠它复现越界。 */
  public int getRowBytes(){ return config==Config.ALPHA_8 ? ((width+3)/4)*4 : width*4; }
  public void getPixels(int[] out, int off, int stride, int x, int y, int w, int h) {
    for (int j=0;j<h;j++) System.arraycopy(px, (y + j) * width + x, out, off + j * stride, w);
  }
  /* ⚠ Android 的 setPixels 是 (colors, **offset, stride**, …)，与 getPixels 的 (colors, **stride, offset**, …) 顺序相反 ——
     这是平台的真实不一致，桩类必须照抄，否则喂进去的图每行都是同一行。 */
  public void setPixels(int[] src, int off, int stride, int x, int y, int w, int h) {
    for (int j=0;j<h;j++) System.arraycopy(src, off + j * stride, px, (y + j) * width + x, w);
  }
  public void recycle(){ px=null; }
  public String toString(){ return "Bitmap("+width+"x"+height+" "+config+")"; }
}

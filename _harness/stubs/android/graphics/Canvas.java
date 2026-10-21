package android.graphics;
/** 判据台要能真的把像素画进缓冲：ToneCheck 取样、intoVisibleArea 缩放都走这里。 */
public class Canvas {
  private final Bitmap target;
  public Canvas(Bitmap b){ target = b; }
  public void drawColor(int color, PorterDuff.Mode mode){
    java.util.Arrays.fill(target.px, mode == PorterDuff.Mode.SRC ? color : color);
  }
  public void drawBitmap(Bitmap src, Rect srcRect, Rect dst, Paint paint){
    Rect s = srcRect != null ? srcRect : new Rect(0, 0, src.width, src.height);
    for (int y = dst.top; y < dst.bottom; y++) for (int x = dst.left; x < dst.right; x++) {
      int sx = s.left + (x - dst.left) * s.width() / dst.width();
      int sy = s.top + (y - dst.top) * s.height() / dst.height();
      if (sx < src.width && sy < src.height) target.px[y * target.width + x] = src.px[sy * src.width + sx];
    }
  }
  public void drawBitmap(Bitmap src, Matrix m, Paint paint){
    for (int y = 0; y < target.height; y++) for (int x = 0; x < target.width; x++) {
      int sx = (int)(x / m.sx), sy = (int)(y / m.sy);
      if (sx < src.width && sy < src.height) target.px[y * target.width + x] = src.px[sy * src.width + sx];
    }
  }
}

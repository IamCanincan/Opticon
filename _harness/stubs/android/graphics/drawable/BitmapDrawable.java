package android.graphics.drawable;
import android.graphics.Bitmap; import android.graphics.Canvas; import android.graphics.Rect;
public class BitmapDrawable extends Drawable {
  private final Bitmap src;
  public BitmapDrawable(Bitmap b){ src=b; }
  public Bitmap getBitmap(){ return src; }
  public int getIntrinsicWidth(){ return src.width; }
  public int getIntrinsicHeight(){ return src.height; }
  public void draw(Canvas c){ Rect r=getBounds(); c.drawBitmap(src,new Rect(0,0,src.width,src.height),r,null); }
}

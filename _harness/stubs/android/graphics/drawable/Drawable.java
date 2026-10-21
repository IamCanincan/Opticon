package android.graphics.drawable;
import android.graphics.Canvas; import android.graphics.Rect;
public abstract class Drawable {
  private final Rect b = new Rect();
  public Rect getBounds(){ return new Rect(b.left,b.top,b.right,b.bottom); }
  public void setBounds(Rect r){ b.set(r.left,r.top,r.right,r.bottom); }
  public void setBounds(int l,int t,int r,int bb){ b.set(l,t,r,bb); }
  public int getIntrinsicWidth(){ return -1; }
  public int getIntrinsicHeight(){ return -1; }
  public void draw(Canvas c){}
}

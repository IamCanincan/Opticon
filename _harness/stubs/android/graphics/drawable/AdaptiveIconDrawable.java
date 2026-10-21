package android.graphics.drawable;
import android.graphics.Canvas;
public class AdaptiveIconDrawable extends Drawable {
  private final Drawable fg;
  public AdaptiveIconDrawable(Drawable bg, Drawable fg){ this.fg=fg; }
  public Drawable getForeground(){ return fg; }
  public static float getExtraInsetFraction(){ return 0.25f; }
  public void draw(Canvas c){ if (fg!=null) fg.draw(c); }
}

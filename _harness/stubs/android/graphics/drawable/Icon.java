package android.graphics.drawable;
public class Icon {
  public static final int TYPE_BITMAP=2, TYPE_RESOURCE=3, TYPE_DATA=4, TYPE_URI=5,
    TYPE_ADAPTIVE_BITMAP=6, TYPE_URI_ADAPTIVE_BITMAP=7;
  public int getType(){ return TYPE_BITMAP; }
  public int getResId(){ return 0; }
  public Drawable loadDrawable(android.content.Context c){ return null; }
}

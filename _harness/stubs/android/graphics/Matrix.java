package android.graphics;
/** 只实现判据台用到的等比缩放（ToneCheck 取样那条路）。 */
public class Matrix {
  public float sx = 1f, sy = 1f;
  public void reset(){ sx = 1f; sy = 1f; }
  public void setScale(float x, float y, float px, float py){ sx = x; sy = y; }
}

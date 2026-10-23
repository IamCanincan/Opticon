package com.iamcanincan.opticon.circle;

// 只用 MonoKey 的纯函数：数组 under（147k 逐像素折）vs 纯色 under（只折一个颜色）。
// 目的：在开不含糊的 A/B 里量 `keyOfFlat` 省下多少 µs（真机命中账里键那一格是 ~0.65ms）。
public final class KeyCost {
  public static void main(String[] a) {
    final int edge = 384, total = edge * edge;
    int[] px = new int[total];
    int[] under = new int[total];
    for (int i = 0; i < total; i++) {
      px[i] = (int) (0xff000000L | ((i * 2654435761L) & 0xffffffL));
      under[i] = 0xfff40009;                       // 纯色盘
    }
    long s = 0;
    for (int i = 0; i < 300; i++) {               // warmup
      s += MonoKey.INSTANCE.keyOf(px, under, edge, true, false, true);
      s += MonoKey.INSTANCE.keyOfFlat(px, 0xfff40009, edge, true, false, true);
    }
    final int N = 1000;
    long t0 = System.nanoTime();
    for (int i = 0; i < N; i++) s += MonoKey.INSTANCE.keyOf(px, under, edge, true, false, true);
    long t1 = System.nanoTime();
    for (int i = 0; i < N; i++) s += MonoKey.INSTANCE.keyOfFlat(px, 0xfff40009, edge, true, false, true);
    long t2 = System.nanoTime();
    System.out.printf("keyOf(px+under) = %.1f µs/call%n", (t1 - t0) / 1000.0 / N);
    System.out.printf("keyOfFlat(px)   = %.1f µs/call%n", (t2 - t1) / 1000.0 / N);
    System.out.println("sink=" + s);
  }
}

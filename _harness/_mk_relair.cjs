const fs = require("fs");
const p = "cand/MonochromeMask.relAir.kt";
let s = fs.readFileSync(p, "utf8");
let bad = 0;
const rep = (a, b, t) => { if (!s.includes(a)) { console.log("MISS " + t); bad++; return; } s = s.split(a).join(b); console.log("ok   " + t); };

rep("    var gN: Int = 0,",
  "    var gN: Int = 0,\n" +
  "    /** 同一趟里被判成墨的那一半的平均亮度（L ×64）。极性该问「墨比底亮吗」，\n" +
  "     *  不是问「底够不够亮」—— 绝对阈值把橙色盘（L=75）当成深盘，PH 那张就被涂反了。 */\n" +
  "    var iL: Int = 0,", "Pass.iL");
rep("    var gl = 0L; var ga = 0L; var gb = 0L; var gn = 0",
  "    var gl = 0L; var ga = 0L; var gb = 0L; var gn = 0\n    var il = 0L", "il");
rep("          } else inn++",
  "          } else { inn++; il += s.labL[i].toInt() and 0xffff }", "inkL");
rep("    p.gN = gn.toInt()",
  "    p.gN = gn.toInt()\n    p.iL = if (inn > 0) (il / inn).toInt() else 0", "assign");
rep("      (ground.gL shr 6) < TONAL_PLATE_L_MIN",
  "      ground.iL > ground.gL + TONAL_POL_MARGIN", "predicate");
rep("  private const val TONAL_AIR_MIN = 25",
  "  private const val TONAL_AIR_MIN = 25\n" +
  "  /** 「墨比底亮多少」才允许翻极性（L* 单位）。 */\n" +
  "  private const val TONAL_POL_MARGIN = 10 shl 6", "const");

fs.writeFileSync(p, s);
console.log(bad ? "有锚点没命中" : "写好了");

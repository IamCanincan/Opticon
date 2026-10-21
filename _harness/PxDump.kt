package harness
import java.io.File
import javax.imageio.ImageIO
fun main() {
  val f = File("live61").listFiles()!!.first { it.name.contains("translate") }
  val img = ImageIO.read(f)
  val w = img.width; val h = img.height
  fun at(x: Int, y: Int): Int = img.getRGB(x, y)
  fun hex(p: Int) = "%06X".format(p and 0xFFFFFF)
  println("白半 (200,40)=${hex(at(200,40))} (240,40)=${hex(at(240,40))} (300,300)=${hex(at(300,300))}")
  println("G   (60,192)=${hex(at(60,192))} (64,150)=${hex(at(64,150))}")
  println("蓝半 (20,192)=${hex(at(20,192))} (160,192)=${hex(at(160,192))}")
  println("文  (250,192)=${hex(at(250,192))} (300,200)=${hex(at(300,200))}")
}

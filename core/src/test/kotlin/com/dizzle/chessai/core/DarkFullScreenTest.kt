package com.dizzle.chessai.core
import com.dizzle.chessai.core.engine.SimpleEngine
import com.dizzle.chessai.core.vision.*
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
/** Dark board inside a full phone screen whose background matches the dark squares. */
class DarkFullScreenTest {
    @Test fun gridIsNotShiftedByOneSquare() {
        val board = ImageIO.read(javaClass.getResourceAsStream("/dark_e4_flipped.png"))
        val W = 1260; val H = 2800
        val bi = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics()
        g.color = Color(0x161D23); g.fillRect(0, 0, W, H)
        g.color = Color(0x263440); g.fillRect(40, 300, 700, 140); g.fillRect(40, 2250, 900, 140) // player bars
        g.color = Color(0xDDDDDD); g.fillRect(60, 330, 80, 80); g.fillRect(60, 2280, 80, 80)
        g.color = Color(0x2E3B46); g.fillRect(0, 2500, W, 300)
        g.drawImage(board, 0, 700, null)
        g.dispose()
        val px = IntArray(W * H); bi.getRGB(0, 0, W, H, px, 0, W)
        val img = RgbImage(W, H, px)
        val rect = BoardLocator.locate(img)!!
        kotlin.test.assertTrue(kotlin.math.abs(rect.y - 702) <= 6 && kotlin.math.abs(rect.size - 1259) <= 8, "rect $rect")
        val a = Analyzer(PieceLibrary()) { _, _, _ -> ByteArray(0) }
        val r = a.analyze(img, Analyzer.Options(engine = SimpleEngine(16), thinkMs = 200))
        kotlin.test.assertTrue(r.ok && r.myTurn, r.message)
        kotlin.test.assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR", r.fen!!.substringBefore(' '))
    }
}

/** Real full-screen screenshot of Duolingo chess (dark board, start position). */
class DuolingoScreenshotTest {
    @Test fun duolingoStartPosition() {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/duolingo_start_full.png"))
        val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        val img = RgbImage(bi.width, bi.height, px)
        val a = Analyzer(PieceLibrary()) { _, _, _ -> ByteArray(0) }
        val r = a.analyze(img, Analyzer.Options(engine = SimpleEngine(16), thinkMs = 200))
        kotlin.test.assertTrue(r.ok && r.myTurn, r.message)
        kotlin.test.assertEquals(Analyzer.Source.START, r.source)
        kotlin.test.assertEquals(true, a.whiteBottom)
        kotlin.test.assertTrue(kotlin.math.abs(r.rect!!.y - 1005) <= 8, "rect ${r.rect}")
    }
}

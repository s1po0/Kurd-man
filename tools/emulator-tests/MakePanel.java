import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Original fixed synthetic fixture. No reader image, model or runtime APK code. */
class MakePanel {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected synthetic output PNG");
        BufferedImage image = new BufferedImage(960, 820, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(66, 76, 124)); g.fillRect(0, 0, 960, 820);
            g.setColor(Color.BLACK); g.fillRect(48, 50, 864, 720);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
            g.drawString("ASPECT: STONE KEEPER", 100, 154);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            g.drawString("ASPECT DESCRIPTION:", 100, 284);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 34));
            String[] body = {"This rare trait grants two abilities", "and one skill. The bearer can sense",
                    "nearby stones, but cannot move them."};
            for (int i = 0; i < body.length; i++) g.drawString(body[i], 100, 414 + i * 62);
        } finally { g.dispose(); }
        File target = new File(args[0]);
        if (target.exists()) throw new IllegalStateException("Do not overwrite QA evidence");
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot make output directory");
        if (!ImageIO.write(image, "png", target)) throw new IllegalStateException("PNG writer unavailable");
    }
}

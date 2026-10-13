import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Offline atlas budget experiment: preserve layout and normalized UVs. */
public class CompactAtlas {
    public static void main(String[] args) throws Exception {
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        BufferedImage result = new BufferedImage(384, 256, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                  RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, 384, 256, null);
        graphics.dispose();
        if (!ImageIO.write(result, "jpg", Path.of(args[1]).toFile())) {
            throw new IllegalStateException("JPEG writer unavailable");
        }
        System.out.println(source.getWidth() + "x" + source.getHeight() + " -> 384x256");
    }
}

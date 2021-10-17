import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class EncodeDecodeLoop {

    public static void main(String ... args) throws Exception {
        String charsetName = args.length > 0 ? args[0] : "UTF-8";
        Charset charset = Charset.forName(charsetName);
        char[] chars = new char[16384];

        var random = new Random(1L); // Random, but predictably so
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char)(' ' + random.nextInt('z' - ' '));
        }

        int repeat = args.length > 1 ? Integer.parseInt(args[1]) : 50;

        var path = Files.createTempFile("random_ascii", "txt");
        var writer = Files.newBufferedWriter(path, charset);
        var reader = Files.newBufferedReader(path, charset);
        var readChars = new char[16384];

        for (int i = 0; i < repeat; i++) {
            writer.write(chars, 0, chars.length);
            reader.read(readChars, 0, chars.length);
        }
        Files.delete(path);
    }

}

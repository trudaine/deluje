package org.chuck.deluge.xml;

import org.chuck.deluge.model.ProjectModel;
import java.io.File;

public class Lud1ParserTest {
    public static void main(String[] args) throws Exception {
        File f = new File("C:/Users/ludo/delugedownload/ludocard/SONGS/Lud1.XML");
        System.out.println("Loading: " + f.getAbsolutePath() + " exists=" + f.exists());
        ProjectModel pm = DelugeXmlParser.parseSong(f);
        System.out.println("Done. Tracks=" + pm.getTracks().size());
    }
}

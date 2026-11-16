import java.io.*; import java.nio.file.*; import java.util.*; import java.util.stream.*; import java.util.zip.*;
public class UnzipTest {
  // === traduzione fedele di ZipUtils.kt PRIMA del fix ===
  static void oldExtract(ZipInputStream z, ZipEntry e, byte[] buf, String target) throws IOException {
    File file = Paths.get(target, e.getName()).toFile();
    if (e.isDirectory()) { if (!file.isDirectory()) file.mkdirs(); return; }
    try (FileOutputStream o = new FileOutputStream(file)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
  }
  // === traduzione fedele di ZipUtils.kt DOPO il fix ===
  static String trimStart(String s){ int i=0; while(i<s.length() && (s.charAt(i)=='/'||s.charAt(i)=='\\')) i++; return s.substring(i); }
  static void newExtract(ZipInputStream z, ZipEntry e, byte[] buf, String target) throws IOException {
    Path root = Paths.get(target).toAbsolutePath().normalize();
    Path p = root.resolve(trimStart(e.getName())).normalize();
    if (!p.startsWith(root)) throw new IOException("Zip entry is outside of the target directory: " + e.getName());
    File file = p.toFile();
    if (e.isDirectory()) { if (!file.isDirectory()) file.mkdirs(); return; }
    File parent = file.getParentFile(); if (parent != null) parent.mkdirs();
    try (FileOutputStream o = new FileOutputStream(file)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
  }
  static int unzip(boolean fixed, byte[] data, String target) throws IOException {
    int count = 0;
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
      byte[] buf = new byte[8192]; ZipEntry e;
      while ((e = zis.getNextEntry()) != null) { if (fixed) newExtract(zis,e,buf,target); else oldExtract(zis,e,buf,target); count++; }
    }
    return count;
  }
  static List<String> tree(Path base) throws IOException {
    try (Stream<Path> s = Files.walk(base)) { return s.filter(Files::isRegularFile).map(p -> base.relativize(p).toString()).sorted().collect(Collectors.toList()); }
  }
  public static void main(String[] a) throws Exception {
    Path work = Paths.get("out"); 
    File[] cases = new File("cases").listFiles(); Arrays.sort(cases);
    System.out.printf("%-26s | %-34s | %-34s%n","caso","PRIMA del fix","DOPO il fix");
    for (File c : cases) {
      byte[] data = Files.readAllBytes(c.toPath()); String[] res = new String[2];
      for (int v = 0; v < 2; v++) {
        Path dir = work.resolve(c.getName().replace(".zip","") + (v==0?"_old":"_new"));
        Path target = dir.resolve("target"); Files.createDirectories(target);
        String outcome; 
        try { int n = unzip(v==1, data, target.toString()); outcome = "OK entry=" + n; }
        catch (Throwable t) { outcome = t.getClass().getSimpleName(); }
        List<String> all = tree(dir);
        long escaped = all.stream().filter(f -> !f.startsWith("target" + File.separator)).count();
        long inside = all.size() - escaped;
        res[v] = outcome + " | in:" + inside + (escaped>0 ? " FUORI:"+escaped : "");
      }
      System.out.printf("%-26s | %-34s | %-34s%n", c.getName().replace(".zip",""), res[0], res[1]);
    }
  }
}

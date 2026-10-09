import com.slukhayka.audiobooks.data.recommend.*;
import ai.onnxruntime.OrtEnvironment;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Diagnostic launcher: exactly one unchanged production embed call. */
public final class R1OneCall {
  static final String INPUT_SHA="e8470a2081d6c37a5eafb54e4cdb91a55b9be171400a994a0596d4e708afa369";
  static final String MODEL_SHA="f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193";
  static final String TOKENIZER_SHA="0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39";
  static String sha(Path p) throws Exception {
    var d=MessageDigest.getInstance("SHA-256");
    try(var in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
    return HexFormat.of().formatHex(d.digest());
  }
  static void pinned(Path p,String expected) throws Exception {
    if(!sha(p).equals(expected))throw new IllegalArgumentException("Pin mismatch: "+p);
  }
  static void vector(Path p,float[] a) throws Exception {
    var b=ByteBuffer.allocate(a.length*4).order(ByteOrder.BIG_ENDIAN);
    for(float v:a){if(!Float.isFinite(v))throw new IllegalStateException("Nonfinite returned vector");b.putInt(Float.floatToRawIntBits(v));}
    Files.write(p,b.array(),StandardOpenOption.CREATE_NEW);
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=4)throw new IllegalArgumentException("model tokenizer fixed-input-tsv existing-output-dir");
    Path model=Path.of(args[0]), tokenizer=Path.of(args[1]), input=Path.of(args[2]), out=Path.of(args[3]);
    if(!Files.isDirectory(out))throw new IllegalArgumentException("Output directory must already exist");
    pinned(model,MODEL_SHA);pinned(tokenizer,TOKENIZER_SHA);pinned(input,INPUT_SHA);
    var lines=Files.readAllLines(input,StandardCharsets.UTF_8);
    if(lines.size()!=2 || !lines.get(0).equals("idBase64\ttextBase64"))throw new IllegalStateException("Unexpected frozen input format");
    var parts=lines.get(1).split("\t",-1);
    String id=new String(Base64.getDecoder().decode(parts[0]),StandardCharsets.UTF_8);
    String text=new String(Base64.getDecoder().decode(parts[1]),StandardCharsets.UTF_8);
    if(!id.equals("1 2 and 3 john kjv|james king version"))throw new IllegalStateException("Selection changed");
    Files.writeString(out.resolve("work-id.txt"),id,StandardOpenOption.CREATE_NEW);
    Files.write(out.resolve("unprefixed-text.utf8"),text.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    Properties p=new Properties();
    for(String key:List.of("java.home","java.version","java.vm.name","java.vm.version","os.name","os.arch"))p.setProperty(key,System.getProperty(key,""));
    for(String key:System.getProperties().stringPropertyNames())if(key.startsWith("onnxruntime."))p.setProperty(key,System.getProperty(key));
    for(Class<?> c:List.of(OnnxEmbedder.class,UnigramTokenizer.class,E5RecommendationInput.class,OrtEnvironment.class))
      p.setProperty("origin."+c.getName(),c.getProtectionDomain().getCodeSource().getLocation().toString());
    p.setProperty("model.sha256",sha(model));p.setProperty("tokenizer.sha256",sha(tokenizer));p.setProperty("input.sha256",sha(input));
    try(var file=Files.newOutputStream(out.resolve("launcher-provenance.properties"),StandardOpenOption.CREATE_NEW)){p.store(file,"Actual launcher observations; not native-library mapping or kernel/provider evidence");}
    OnnxEmbedder embedder=OnnxEmbedder.Companion.fromFiles(model.toFile(),tokenizer.toFile(),"query: ");
    if(embedder==null)throw new IllegalStateException("Actual pinned embedder did not initialize; no fallback");
    try(embedder){
      float[] returned=embedder.embed(text); // The sole inference call. No copied production path.
      if(returned.length!=384)throw new IllegalStateException("Wrong returned dimension");
      vector(out.resolve("launcher-return.f32be"),returned);
    }
  }
}

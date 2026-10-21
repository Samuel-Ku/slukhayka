import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Private bounded observer. No class replacement or copied inference/pooling. */
public final class R1Observe {
  static final String EMBED="com.slukhayka.audiobooks.data.recommend.OnnxEmbedder";
  static final String TOKEN="com.slukhayka.audiobooks.data.recommend.UnigramTokenizer";
  static final String CLASS_SHA="2d3d8db1a25a40ac30dd3a053be81790ea22f2bbb6daf5181ec35cb3d4e750d4";
  static final String ORT_SHA="5b57b8c6303303f8d8cbed7f787f31611718b746ab2fa45c888c9df153f1b3c2";
  final Path out;
  final Properties facts=new Properties();
  VirtualMachine vm;
  ThreadReference owner;
  ObjectReference instance;
  int tokenCalls, inputStops, hiddenStops, pooledStops, poolReturns, embedReturns;
  boolean getterActive;
  MethodExitRequest actualReturns;
  final Set<String> prepared=new HashSet<>();
  R1Observe(Path out){this.out=out;}
  static void require(boolean b,String reason){if(!b)throw new IllegalStateException(reason);}
  static String sha(Path p) throws Exception {return R1OneCall.sha(p);}
  static Value field(ObjectReference o,String name){
    Field f=o.referenceType().fieldByName(name);require(f!=null,"Missing actual field "+name+" on "+o.referenceType().name());return o.getValue(f);
  }
  static ArrayReference array(Value v){require(v instanceof ArrayReference,"Expected array, got "+v);return (ArrayReference)v;}
  static ObjectReference object(Value v){require(v instanceof ObjectReference,"Expected object");return (ObjectReference)v;}
  static String string(Value v){require(v instanceof StringReference,"Expected String");return ((StringReference)v).value();}
  static int integer(Value v){require(v instanceof IntegerValue,"Expected int");return ((IntegerValue)v).value();}
  static long longValue(Value v){require(v instanceof LongValue,"Expected long");return ((LongValue)v).value();}
  void bytes(String name,byte[] bytes) throws Exception {Files.write(out.resolve(name),bytes,StandardOpenOption.CREATE_NEW);}
  byte[] floats(ArrayReference a){
    ByteBuffer b=ByteBuffer.allocate(Math.multiplyExact(4,a.length())).order(ByteOrder.BIG_ENDIAN);
    for(Value v:a.getValues()){require(v instanceof FloatValue,"Expected primitive Float32");float f=((FloatValue)v).value();require(Float.isFinite(f),"Nonfinite observed value");b.putInt(Float.floatToRawIntBits(f));}
    return b.array();
  }
  byte[] longs(ArrayReference a,int start,int n){
    require(start>=0 && n>=0 && start+n<=a.length(),"Invalid buffer range");
    var b=ByteBuffer.allocate(Math.multiplyExact(8,n)).order(ByteOrder.BIG_ENDIAN);
    for(Value v:a.getValues(start,n))b.putLong(longValue(v));return b.array();
  }
  void heapLongBuffer(ObjectReference buffer,String name,int expected) throws Exception {
    int position=integer(field(buffer,"position")), limit=integer(field(buffer,"limit")), offset=integer(field(buffer,"offset"));
    require(limit-position==expected,"Unexpected observed buffer length");
    facts.setProperty(name+".class",buffer.referenceType().name());facts.setProperty(name+".position",Integer.toString(position));facts.setProperty(name+".limit",Integer.toString(limit));
    bytes(name+".i64be",longs(array(field(buffer,"hb")),offset+position,expected));
  }
  void heapFloatBuffer(ObjectReference buffer,String name,int expected) throws Exception {
    int position=integer(field(buffer,"position")), limit=integer(field(buffer,"limit")), offset=integer(field(buffer,"offset"));
    require(limit-position==expected,"Unexpected observed output buffer length");
    ArrayReference hb=array(field(buffer,"hb"));require(offset+position+expected<=hb.length(),"Invalid FloatBuffer range");
    var b=ByteBuffer.allocate(Math.multiplyExact(4,expected)).order(ByteOrder.BIG_ENDIAN);
    for(Value v:hb.getValues(offset+position,expected)){require(v instanceof FloatValue,"Expected Float32 buffer");float f=((FloatValue)v).value();require(Float.isFinite(f),"Nonfinite native-read output");b.putInt(Float.floatToRawIntBits(f));}
    facts.setProperty(name+".class",buffer.referenceType().name());bytes(name+".f32be",b.array());
  }
  int tensorInfo(ObjectReference tensor,String name,String dtype) {
    ObjectReference info=object(field(tensor,"info"));ArrayReference shape=array(field(info,"shape"));
    List<Long> dims=new ArrayList<>();long total=1;
    for(Value v:shape.getValues()){long n=longValue(v);require(n>0 && n<=512,"Unexpected tensor dimension");dims.add(n);total=Math.multiplyExact(total,n);}
    require(total<=512L*384,"Diagnostic tensor exceeds fixed budget");
    String actual=string(field(object(field(info,"type")),"name"));require(actual.equals(dtype),"Unsupported tensor dtype: "+actual);
    facts.setProperty(name+".shape",dims.toString());facts.setProperty(name+".dtype",actual);facts.setProperty(name+".objectId",Long.toString(tensor.uniqueID()));
    return Math.toIntExact(total);
  }
  Value getter(ObjectReference tensor,String name,ThreadReference thread) throws Exception {
    List<Method> methods=tensor.referenceType().methodsByName(name);require(methods.size()==1,"Ambiguous actual getter "+name);
    List<EventRequest> enabled=new ArrayList<>();
    for(EventRequest r:vm.eventRequestManager().breakpointRequests())if(r.isEnabled()){r.disable();enabled.add(r);}
    for(EventRequest r:vm.eventRequestManager().methodExitRequests())if(r.isEnabled()){r.disable();enabled.add(r);}
    getterActive=true;
    try{return tensor.invokeMethod(thread,methods.get(0),List.of(),ObjectReference.INVOKE_SINGLE_THREADED);}
    finally{getterActive=false;for(EventRequest r:enabled)r.enable();}
  }
  static Value local(StackFrame f,String name) throws Exception {
    LocalVariable v=f.visibleVariableByName(name);require(v!=null,"Missing visible local "+name);return f.getValue(v);
  }
  void addStop(ReferenceType type,String method,int line,long code) throws Exception {
    List<Location> candidates=new ArrayList<>();for(Method m:type.methodsByName(method))candidates.addAll(m.locationsOfLine("Java",null,line));
    Location found=null;for(Location l:candidates)if(l.codeIndex()==code){require(found==null,"Duplicate stop");found=l;}
    require(found!=null,"Pinned debug stop unavailable: "+method+":"+line+"/"+code);
    var b=vm.eventRequestManager().createBreakpointRequest(found);b.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);b.enable();
  }
  void prepare(ReferenceType type) throws Exception {
    if(!prepared.add(type.name()))return;
    if(type.name().equals(TOKEN)) {
      List<Method> methods=type.methodsByName("encodeForModel","(Ljava/lang/String;I)[I");require(methods.size()==1,"Missing actual tokenizer method");
      var r=vm.eventRequestManager().createBreakpointRequest(methods.get(0).location());r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);r.enable();
    } else if(type.name().equals(EMBED)) {
      addStop(type,"embed",61,214);addStop(type,"embed",64,477);addStop(type,"meanPool",99,311);
      actualReturns=vm.eventRequestManager().createMethodExitRequest();actualReturns.addClassFilter(type);actualReturns.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
    }
  }
  boolean selectedTokenizer(ThreadReference thread) throws Exception {
    for(StackFrame f:thread.frames())if(f.location().declaringType().name().equals(EMBED) && f.location().method().name().equals("embed"))return true;
    return false;
  }
  void stop(BreakpointEvent e) throws Exception {
    require(!getterActive,"Unexpected breakpoint during native-read getter");
    ThreadReference thread=e.thread();StackFrame f=thread.frame(0);String type=e.location().declaringType().name(), method=e.location().method().name();
    if(type.equals(TOKEN)) {
      if(!selectedTokenizer(thread))return;require(++tokenCalls==1,"More than one selected tokenizer call");
      List<Value> args=f.getArgumentValues();require(args.size()==2 && integer(args.get(1))==512,"Changed production token budget");
      bytes("actual-prefixed-text.utf8",string(args.get(0)).getBytes(StandardCharsets.UTF_8));return;
    }
    if(method.equals("embed") && e.location().codeIndex()==214) {
      require(++inputStops==1 && tokenCalls==1,"Unexpected embed input call count");owner=thread;instance=f.thisObject();
      int seq=integer(local(f,"seqLen"));require(seq>0 && seq<=512,"Changed sequence budget");facts.setProperty("actual.seqLen",Integer.toString(seq));
      var ids=array(local(f,"ids"));require(ids.length()==seq,"IDs length mismatch");var idBytes=ByteBuffer.allocate(seq*4).order(ByteOrder.BIG_ENDIAN);for(Value v:ids.getValues())idBytes.putInt(integer(v));bytes("actual-token-ids.i32be",idBytes.array());
      for(String n:List.of("inputIds","attention","tokenTypes"))heapLongBuffer(object(local(f,n)),"heap-"+n,seq);
      ArrayReference shape=array(local(f,"shape"));require(shape.length()==2 && longValue(shape.getValue(0))==1 && longValue(shape.getValue(1))==seq,"Changed production input shape");
      ObjectReference map=object(local(f,"inputs"));ObjectReference entry=(ObjectReference)field(map,"head");
      Map<String,ObjectReference> actual=new LinkedHashMap<>();
      while(entry!=null){require(actual.size()<3,"Too many bound input tensors");String key=string(field(entry,"key"));require(!actual.containsKey(key),"Duplicate actual input name");actual.put(key,object(field(entry,"value")));entry=(ObjectReference)field(entry,"after");}
      Set<String> expected=new HashSet<>(List.of(string(field(instance,"inputIdsName")),string(field(instance,"attentionMaskName"))));
      Value typeName=field(instance,"tokenTypeIdsName");if(typeName!=null)expected.add(string(typeName));require(actual.keySet().equals(expected),"Actual input map differs from selected names");
      int index=0;for(var input:actual.entrySet()){
        String key="bound-input-"+index++;facts.setProperty(key+".name",input.getKey());require(tensorInfo(input.getValue(),key,"INT64")==seq,"Input tensor shape mismatch");
        heapLongBuffer(object(getter(input.getValue(),"getLongBuffer",thread)),key,seq);
      }
      facts.setProperty("bound-input.count",Integer.toString(actual.size()));facts.setProperty("selected-output.name",string(field(instance,"outputName")));
      require(actualReturns!=null && !actualReturns.isEnabled(),"Actual return request must remain disabled until input stop");
      actualReturns.addThreadFilter(owner);actualReturns.enable();return;
    }
    require(owner!=null && thread.equals(owner) && f.thisObject().equals(instance),"Observer lost the same production call");
    if(method.equals("embed") && e.location().codeIndex()==477) {
      require(++hiddenStops==1 && inputStops==1,"Unexpected selected output stop count");
      ArrayReference hidden=array(local(f,"hidden"));ObjectReference output=object(local(f,"output"));
      int seq=Integer.parseInt(facts.getProperty("actual.seqLen"));int count=tensorInfo(output,"selected-output","FLOAT");require(count==seq*384,"Changed selected hidden tensor shape");
      require(hidden.length()==1,"Unexpected hidden batch");ArrayReference rows=array(hidden.getValue(0));require(rows.length()==seq,"Unexpected hidden sequence");
      var flat=ByteBuffer.allocate(count*4).order(ByteOrder.BIG_ENDIAN);facts.setProperty("hidden.javaClass",hidden.referenceType().name());
      for(Value row:rows.getValues()){ArrayReference a=array(row);require(a.length()==384,"Unexpected hidden row dimension");flat.put(floats(a));}
      bytes("actual-hidden-java.f32be",flat.array());
      heapFloatBuffer(object(getter(output,"getFloatBuffer",thread)),"actual-hidden-native-read",count);return;
    }
    if(method.equals("meanPool") && e.location().codeIndex()==311) {
      require(++pooledStops==1 && hiddenStops==1,"Unexpected production pooling stop count");ArrayReference pooled=array(local(f,"pooled"));require(pooled.length()==384,"Wrong actual pooled length");bytes("actual-pooled-sum.f32be",floats(pooled));
      Value v=local(f,"norm");require(v instanceof DoubleValue,"Expected actual norm Double");double norm=((DoubleValue)v).value();require(Double.isFinite(norm),"Nonfinite actual norm");bytes("actual-norm.f64be",ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(norm)).array());return;
    }
    throw new IllegalStateException("Unexpected production breakpoint");
  }
  void returned(MethodExitEvent e) throws Exception {
    String method=e.method().name();if(!method.equals("embed") && !method.equals("meanPool"))return;
    require(owner!=null && e.thread().equals(owner),"Return from another call");
    if(method.equals("meanPool")){require(++poolReturns==1 && pooledStops==1,"Unexpected actual pool return");bytes("actual-pool-return.f32be",floats(array(e.returnValue())));}
    else{require(++embedReturns==1 && poolReturns==1,"Unexpected actual embed return");bytes("actual-embed-return.f32be",floats(array(e.returnValue())));}
  }
  static String quoted(String s){require(!s.contains("\"") && !s.contains("\n") && !s.contains("\r"),"Unsupported connector argument quoting");return "\""+s+"\"";}
  static void cpPins(String cp) throws Exception {
    Path first=null,ort=null;
    for(String value:cp.split(java.io.File.pathSeparator)){
      Path p=Path.of(value);
      if(Files.isDirectory(p) && first==null){Path c=p.resolve(EMBED.replace('.','/')+".class");if(Files.isRegularFile(c))first=c;}
      if(p.getFileName().toString().equals("onnxruntime-1.21.0.jar")){require(ort==null,"Duplicate desktop ORT JAR");ort=p;}
      require(!p.toString().contains("onnxruntime-android"),"Android ORT cannot be in diagnostic classpath");
    }
    require(first!=null && sha(first).equals(CLASS_SHA),"Production embed class bytes changed");require(ort!=null && sha(ort).equals(ORT_SHA),"Desktop ORT JAR pin mismatch");
  }
  void run(String javaHome,String cp,String model,String tokenizer,String input) throws Exception {
    cpPins(cp);
    LaunchingConnector connector=null;for(LaunchingConnector c:Bootstrap.virtualMachineManager().launchingConnectors())if(c.name().equals("com.sun.jdi.CommandLineLaunch"))connector=c;
    require(connector!=null,"JDI CommandLineLaunch unavailable");var args=connector.defaultArguments();args.get("home").setValue(javaHome);
    args.get("options").setValue("-Xmx768m -cp "+quoted(cp));
    args.get("main").setValue("R1OneCall "+quoted(model)+" "+quoted(tokenizer)+" "+quoted(input)+" "+quoted(out.toString()));args.get("suspend").setValue("true");
    vm=connector.launch(args);Process process=vm.process();
    var deadline=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"owned-VM-timeout");t.setDaemon(true);return t;});
    var forced=new java.util.concurrent.atomic.AtomicBoolean();
    deadline.schedule(()->{forced.set(true);process.destroy();try{if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException x){Thread.currentThread().interrupt();}},180,TimeUnit.SECONDS);
    var streams=Executors.newFixedThreadPool(2);
    var stdout=streams.submit(()->{try(var f=Files.newOutputStream(out.resolve("vm.stdout"),StandardOpenOption.CREATE_NEW)){process.getInputStream().transferTo(f);}return null;});
    var stderr=streams.submit(()->{try(var f=Files.newOutputStream(out.resolve("vm.stderr"),StandardOpenOption.CREATE_NEW)){process.getErrorStream().transferTo(f);}return null;});
    boolean completed=false;
    try {
      require(vm.canGetMethodReturnValues(),"JDI cannot observe actual method returns");
      for(String type:List.of(EMBED,TOKEN)){ClassPrepareRequest r=vm.eventRequestManager().createClassPrepareRequest();r.addClassFilter(type);r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);r.enable();for(ReferenceType t:vm.classesByName(type))prepare(t);}
      boolean dead=false;
      while(!dead){EventSet set=vm.eventQueue().remove(1000);require(!forced.get(),"Owned VM exceeded 180-second bound");if(set==null)continue;
        try{for(Event e:set){if(e instanceof ClassPrepareEvent p)prepare(p.referenceType());else if(e instanceof BreakpointEvent b)stop(b);else if(e instanceof MethodExitEvent m)returned(m);else if(e instanceof VMDeathEvent || e instanceof VMDisconnectEvent)dead=true;}}
        finally{try{set.resume();}catch(VMDisconnectedException ignored){}}
      }
      require(process.waitFor(10,TimeUnit.SECONDS),"Owned VM did not close after disconnect");stdout.get(10,TimeUnit.SECONDS);stderr.get(10,TimeUnit.SECONDS);
      require(process.exitValue()==0 && !forced.get(),"Actual launcher failed or forced timeout");
      require(tokenCalls==1 && inputStops==1 && hiddenStops==1 && pooledStops==1 && poolReturns==1 && embedReturns==1,"Incomplete same-call boundary observations");
      require(Arrays.equals(Files.readAllBytes(out.resolve("actual-embed-return.f32be")),Files.readAllBytes(out.resolve("launcher-return.f32be"))),"Observer and actual caller return differ");
      facts.setProperty("hidden.nativeReadEqualsJava",Boolean.toString(Arrays.equals(Files.readAllBytes(out.resolve("actual-hidden-java.f32be")),Files.readAllBytes(out.resolve("actual-hidden-native-read.f32be")))));
      facts.setProperty("verdict","OBSERVED_ONE_CURRENT_PRODUCTION_CALL_BOUNDARIES_ONLY");facts.setProperty("nativeLibraryMappingObserved","false");facts.setProperty("kernelOrPlatformCauseProven","false");facts.setProperty("linuxParityOrQualityGO","false");completed=true;
    } finally {
      deadline.shutdownNow();
      if(!completed && process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();require(process.waitFor(5,TimeUnit.SECONDS),"Owned diagnostic VM still alive");}
      streams.shutdownNow();facts.setProperty("complete",Boolean.toString(completed));facts.setProperty("forcedTimeout",Boolean.toString(forced.get()));
      try(var f=Files.newOutputStream(out.resolve("observer.properties"),StandardOpenOption.CREATE_NEW)){facts.store(f,"Actual diagnostics only after separately authorized execution");}
    }
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=6)throw new IllegalArgumentException("java-home classpath model tokenizer fixed-input-tsv new-output-dir");
    Path out=Path.of(args[5]);Files.createDirectory(out); // Refuse reuse of any previous result folder.
    new R1Observe(out).run(args[0],args[1],args[2],args[3],args[4]);
  }
}

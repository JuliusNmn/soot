package soot.toDex;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.common.io.Files;

import junit.framework.AssertionFailedError;
import soot.Body;
import soot.G;
import soot.G.GlobalObjectGetter;
import soot.Singletons.Global;
import soot.SootClass;
import soot.Trap;
import soot.Unit;

public class TrapSplitterTest {

  private List<LinkedHashMap<SootClass, Integer>> getTrapMapping(Body b) {
    int i = 0;
    List<LinkedHashMap<SootClass, Integer>> mapping = new ArrayList<>();
    LinkedHashMap<Unit, Integer> unitToIndex = new LinkedHashMap<>();
    for (Unit u : b.getUnits()) {
      unitToIndex.put(u, i);
      mapping.add(null);
      i++;
    }
    for (Trap t : b.getTraps()) {
      for (Unit unit = t.getBeginUnit(); unit != t.getEndUnit(); unit = b.getUnits().getSuccOf(unit)) {
        // check whether the unit already has a trap for this type of exception
        int index = unitToIndex.get(unit);
        LinkedHashMap<SootClass, Integer> gotos = mapping.get(index);
        if (gotos == null) {
          gotos = new LinkedHashMap<>();
          mapping.set(index, gotos);
        }
        // only include the first occurence
        if (gotos.containsKey(t.getException())) {
          continue;
        }
        gotos.put(t.getException(), unitToIndex.get(t.getHandlerUnit()));
      }
    }
    return mapping;
  }

  /**
   * Compares two bodies (with identical units) for semantic equivalence regarding
   * traps.
   * 
   * @param originalBody
   * @param transformedBody
   */
  private void compareTraps(Body originalBody, Body transformedBody) {
    if (originalBody.getUnits().size() != transformedBody.getUnits().size())
      throw new IllegalArgumentException("Bodies not of equal length");
    if (originalBody.getTraps().size() == 0)
      return;
    List<LinkedHashMap<SootClass, Integer>> trapMappinga = getTrapMapping(originalBody);
    List<LinkedHashMap<SootClass, Integer>> trapMappingb = getTrapMapping(transformedBody);

    if (!trapMappinga.equals(trapMappingb)) {
      throw new AssertionFailedError("Trap statements not semantically equal after transform:\n" + "Original body:\n"
          + originalBody.toString() + "\nTransformed Body:\n" + transformedBody.toString());
    }
  }

  private class SpecialTrapSplitter extends TrapSplitter {

    public SpecialTrapSplitter(Global g) {
      super(g);
    }

    @Override
    protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
      if (b.getTraps().size() < 3)
        return;
      if (b.getMethod().getSignature().equals(
          "<com.google.bitcoin.net.discovery.IrcDiscovery: java.net.InetSocketAddress[] getPeers(long,java.util.concurrent.TimeUnit)>"))
        System.out.println("woot");
      Body bodyBeforeTransform = (Body) b.clone();
      Body legacyBody = (Body) b.clone();
      super.internalTransform(b, phaseName, options);
      compareTraps(bodyBeforeTransform, b);
      ((SpecialG) G.v()).soot_toDex_Legacy_TrapSplitter().transform(legacyBody);
      compareTraps(legacyBody, b);
    }

  }

  private class SpecialG extends G {

    private TrapSplitter instance_soot_toDex_TrapSplitter;
    private LegacyTrapSplitter instance_soot_toDex_Legacy_TrapSplitter;

    @Override
    public TrapSplitter soot_toDex_TrapSplitter() {
      if (instance_soot_toDex_TrapSplitter == null) {
        synchronized (this) {
          if (instance_soot_toDex_TrapSplitter == null)
            instance_soot_toDex_TrapSplitter = new SpecialTrapSplitter(g);
        }
      }
      return instance_soot_toDex_TrapSplitter;
    }

    public LegacyTrapSplitter soot_toDex_Legacy_TrapSplitter() {
      if (instance_soot_toDex_Legacy_TrapSplitter == null) {
        synchronized (this) {
          if (instance_soot_toDex_Legacy_TrapSplitter == null)
            instance_soot_toDex_Legacy_TrapSplitter = new LegacyTrapSplitter(g);
        }
      }
      return instance_soot_toDex_Legacy_TrapSplitter;
    }
  }

  @Test
  public void sampleTest() {
    equivalenceTest("/home/naeumann/Documents/android-platforms",
        "/home/naeumann/code/callbackanalysis/apks/volley.apk", ".");
  }

  public void equivalenceTest(String androidjars, String apk, String outputdir) {
    G oldInstance = G.v();
    // replace soot instance with special wrapper instance
    G.setGlobalObjectGetter(new GlobalObjectGetter() {
      SpecialG g = new SpecialG();

      @Override
      public void reset() {
        g = new SpecialG();
      }

      @Override
      public G getG() {
        return g;
      }
    });
    try {
      soot.Main.main(new String[] { "-android-jars", androidjars, "-src-prec", "apk", "-f", "dex",
          "--allow-phantom-refs", "-process-dir", apk, "-validate", "-d", outputdir });
    } catch (Exception e) {
      throw e;
    } finally {// restore original instance
      G.setGlobalObjectGetter(new GlobalObjectGetter() {

        private G instance = oldInstance;

        @Override
        public G getG() {
          return instance;
        }

        @Override
        public void reset() {
          instance = new G();
        }
      });
    }
  }

  public static void main(String[] args) {
    if (args.length != 2) {
      System.out.println("Args: [directory containing android jars] [directory containing apks]");
      return;
    }
    String androidjars = args[0];
    String searchDirPath = args[1];
    File searchDir = new File(searchDirPath);
    File outDir = Files.createTempDir();
    for (File f : searchDir.listFiles()) {

      String apk = f.getAbsolutePath();

      if (!apk.endsWith(".apk"))
        continue;

      if (outDir.exists()) {
        deleteDirectory(outDir);
      }
      outDir.mkdir();

      try {
        new TrapSplitterTest().equivalenceTest(androidjars, apk, outDir.getAbsolutePath());
      } catch (AssertionFailedError e) {
        throw e;
      } catch (Throwable e) {

        e.printStackTrace();
      }
    }
  }

  public static boolean deleteDirectory(File directory) {
    if (directory.exists()) {
      File[] files = directory.listFiles();
      if (null != files) {
        for (int i = 0; i < files.length; i++) {
          if (files[i].isDirectory()) {
            deleteDirectory(files[i]);
          } else {
            files[i].delete();
          }
        }
      }
    }
    return (directory.delete());
  }

}

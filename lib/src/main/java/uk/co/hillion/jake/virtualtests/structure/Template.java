package uk.co.hillion.jake.virtualtests.structure;

import uk.co.hillion.jake.virtualtests.providers.Environment;
import uk.co.hillion.jake.virtualtests.providers.Node;

import java.io.IOException;
import java.util.List;

/** Provide a Template for a Blueprint, that produces a Node on building the Blueprint. */
public abstract class Template {
  public final Distribution dist;

  public Template(Distribution dist) {
    this.dist = dist;
  }

  public abstract List<SetupStage> getSetup();

  public int getInterfaces() {
    return 1;
  }

  public String getName() {
    return "VirtualTests";
  }

  public int getCoreCount() {
    return 2;
  }

  public int getMemoryMb() {
    return 2048;
  }

  public Blueprint getSoloBlueprint() {
    return new Blueprint() {
      @Override
      public List<Template> getNodes() {
        return List.of(Template.this);
      }

      @Override
      public List<BridgeRequest> getBridges() {
        return List.of();
      }
    };
  }

  public interface SetupFunction {
    void setup(Environment environment, Node node) throws IOException;
  }

  public static class SetupStage implements Comparable<SetupStage> {
    private final SetupFunction foo;
    private final int order;

    public SetupStage(SetupFunction foo) {
      this.foo = foo;
      this.order = Integer.MAX_VALUE / 2;
    }

    public SetupStage(SetupFunction foo, int order) {
      this.foo = foo;
      this.order = order;
    }

    public int getOrder() {
      return order;
    }

    public SetupFunction getFoo() {
      return foo;
    }

    @Override
    public int compareTo(SetupStage that) {
      return Integer.compare(this.order, that.order);
    }
  }
}

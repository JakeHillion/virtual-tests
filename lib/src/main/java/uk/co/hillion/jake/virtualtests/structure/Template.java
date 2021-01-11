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

  public abstract List<SetupStage> getSetup();

  public interface SetupFunction {
    void setup(Environment environment, Node node) throws IOException;
  }

  public static class SetupStage {
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
  }
}

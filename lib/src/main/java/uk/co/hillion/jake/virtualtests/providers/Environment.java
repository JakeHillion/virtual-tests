package uk.co.hillion.jake.virtualtests.providers;

import java.io.IOException;
import java.util.List;

public class Environment implements AutoCloseable {
  private final List<Node> nodes;
  private final List<Bridge> bridges;

  Environment(List<Node> nodes, List<Bridge> bridges) {
    this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
    this.bridges = bridges == null ? List.of() : List.copyOf(bridges);
  }

  public List<Node> getNodes() {
    return List.copyOf(nodes);
  }

  public List<Bridge> getBridges() {
    return List.copyOf(bridges);
  }

  @Override
  public void close() throws IOException {
    for (Node n : getNodes()) {
      n.close();
    }
    if (getNodes().size() > 0) {
      getNodes().get(0).closeAll();
    }

    for (Bridge b : getBridges()) {
      b.close();
    }
    if (getBridges().size() > 0) {
      getBridges().get(0).closeAll();
    }
  }
}

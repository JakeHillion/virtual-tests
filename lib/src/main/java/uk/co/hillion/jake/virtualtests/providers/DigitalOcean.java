package uk.co.hillion.jake.virtualtests.providers;

import uk.co.hillion.jake.virtualtests.structure.Blueprint;
import uk.co.hillion.jake.virtualtests.structure.Template;
import uk.co.hillion.jake.virtualtests.structure.Distribution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class DigitalOcean implements Provider {
  private final String apiKey;
  private final Map<Distribution, String> slugMap;

  public DigitalOcean(String apiKey, Map<Distribution, String> slugMap) {
    this.apiKey = apiKey;
    this.slugMap = new EnumMap<>(slugMap);
  }

  @Override
  public Environment build(Blueprint blueprint) throws ImpossibleBlueprintException {
    if (blueprint.getBridges().size() > 0) {
      throw new ImpossibleBlueprintException(this, "does not support bridges");
    }

    List<Droplet> droplets = new ArrayList<>();

    for (Template template : blueprint.getNodes()) {
      droplets.add(buildDroplet(template));
    }

    return null;
  }

  private Droplet buildDroplet(Template template) {
    return null;
  }

  public class Droplet extends Node {
    @Override
    public void close() throws IOException {

    }
  }
}

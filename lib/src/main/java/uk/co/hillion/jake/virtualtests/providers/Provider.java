package uk.co.hillion.jake.virtualtests.providers;

import uk.co.hillion.jake.virtualtests.structure.Blueprint;

import java.io.IOException;

public interface Provider {
  Environment build(Blueprint blueprint) throws ImpossibleBlueprintException, IOException;
}

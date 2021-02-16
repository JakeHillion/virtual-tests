package uk.co.hillion.jake.virtualtests.structure;

import java.util.List;

/**
 * The Blueprint class is used to provide a requested structure to a provider, which will then
 * create a concrete implementation of the structure.
 */
public interface Blueprint {
  List<Template> getNodes();

  List<BridgeRequest> getBridges();
}

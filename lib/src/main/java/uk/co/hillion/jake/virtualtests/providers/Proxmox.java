package uk.co.hillion.jake.virtualtests.providers;

import uk.co.hillion.jake.proxmox.ProxmoxAPI;
import uk.co.hillion.jake.proxmox.Qemu;
import uk.co.hillion.jake.proxmox.Task;
import uk.co.hillion.jake.virtualtests.structure.Blueprint;
import uk.co.hillion.jake.virtualtests.structure.BridgeRequest;
import uk.co.hillion.jake.virtualtests.structure.Distribution;
import uk.co.hillion.jake.virtualtests.structure.Template;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class Proxmox implements Provider {
  private final long RefreshInterval = 1000L;

  private final ProxmoxAuth auth;
  private final Map<Distribution, Integer> templateMap;
  private final ProxmoxAPI api;

  public Proxmox(ProxmoxAuth auth, Map<Distribution, Integer> templateMap) {
    this(auth, templateMap, true);
  }

  public Proxmox(ProxmoxAuth auth, Map<Distribution, Integer> templateMap, boolean verifySsl) {
    this.auth = auth;
    this.templateMap = new EnumMap<>(templateMap);

    api = new ProxmoxAPI(auth.host, auth.user, auth.tokenName, auth.token, verifySsl);
  }

  @Override
  public Environment build(Blueprint blueprint) throws ImpossibleBlueprintException, IOException {
    // Check blueprint compatibility
    for (Template t : blueprint.getNodes()) {
      if (!templateMap.containsKey(t.dist)) {
        throw new ImpossibleBlueprintException(
            this, String.format("Proxmox missing template id for distribution `%s`", t.dist));
      }
    }

    for (BridgeRequest b : blueprint.getBridges()) {
      throw new ImpossibleBlueprintException(
          this, "Proxmox cannot build bridges (not implemented)");
    }

    // Build blueprint
    List<Node> machines = new ArrayList<>(blueprint.getNodes().size());
    for (Template t : blueprint.getNodes()) {
      machines.add(buildMachine(t));
    }

    // Setup blueprint
    return new Environment(machines, null);
  }

  private Machine buildMachine(Template template) throws IOException {
    Integer toClone = templateMap.get(template.dist);
    // TODO: Not 100
    int newId = 100;

    // TODO: Not fixed name
    String cloneJob =
        api.node(auth.node)
            .qemu(toClone)
            .clone(
                new Qemu.Clone(newId)
                    .setName("VirtualTests")
                    .setDescription("created by virtualtests"));

    awaitTask(cloneJob);
    return new Machine(newId);
  }

  private void awaitTask(String job) throws IOException {
    awaitTask(job, 30000);
  }

  private void awaitTask(String upid, long timeoutMillis) throws IOException {
    long startTime = System.currentTimeMillis();
    while (System.currentTimeMillis() < startTime + timeoutMillis) {
      if (api.node(auth.node).task(upid).status().getStatus() == Task.Status.EStatus.STOPPED) {
        return;
      }

      try {
        Thread.sleep(RefreshInterval);
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
    }

    throw new IOException("proxmox failed within timeout");
  }

  public static class ProxmoxAuth {
    private final String host;
    private final String node;
    private final String user;
    private final String tokenName;
    private final String token;

    public ProxmoxAuth(String host, String node, String user, String tokenName, String token) {
      this.host = host;
      this.node = node;
      this.user = user;
      this.tokenName = tokenName;
      this.token = token;
    }
  }

  public class Machine extends Node {
    private final int id;

    private Machine(int id) {
      this.id = id;
    }

    @Override
    public void close() throws IOException {
      api.node(auth.node).qemu(id).delete();
    }
  }

  public class LinuxBridge extends Bridge {
    private final String bridge;

    public LinuxBridge(String bridge) {
      this.bridge = bridge;
    }

    @Override
    public void close() throws IOException {
      throw new RuntimeException("not implemented");
    }
  }
}

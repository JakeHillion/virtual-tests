package uk.co.hillion.jake.virtualtests.providers;

import com.google.common.net.InetAddresses;
import uk.co.hillion.jake.proxmox.ProxmoxAPI;
import uk.co.hillion.jake.proxmox.Qemu;
import uk.co.hillion.jake.proxmox.QemuConfig;
import uk.co.hillion.jake.proxmox.Task;
import uk.co.hillion.jake.virtualtests.structure.Blueprint;
import uk.co.hillion.jake.virtualtests.structure.BridgeRequest;
import uk.co.hillion.jake.virtualtests.structure.Distribution;
import uk.co.hillion.jake.virtualtests.structure.Template;

import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Proxmox implements Provider {
  private final long RefreshInterval = 1000L;

  private final ProxmoxAuth auth;
  private final ProxmoxConfig config;
  private final ProxmoxAPI api;

  public Proxmox(ProxmoxAuth auth, ProxmoxConfig config) {
    this(auth, config, true);
  }

  public Proxmox(ProxmoxAuth auth, ProxmoxConfig config, boolean verifySsl) {
    this.auth = auth;
    this.config = config;

    api = new ProxmoxAPI(auth.host, auth.user, auth.tokenName, auth.token, verifySsl);
  }

  @Override
  public Environment build(Blueprint blueprint) throws ImpossibleBlueprintException, IOException {
    // Check blueprint compatibility
    for (Template t : blueprint.getNodes()) {
      checkTemplate(t);
    }

    for (BridgeRequest b : blueprint.getBridges()) {
      checkBridgeRequest(b);
    }

    // Build environment for blueprint
    List<Node> machines = new ArrayList<>(blueprint.getNodes().size());
    for (Template t : blueprint.getNodes()) {
      machines.add(buildMachine(t));
    }

    Environment env = new Environment(machines, null);

    // Setup environment according to blueprint
    List<Map.Entry<Node, Template.SetupStage>> stages =
        machines.stream()
            .map(n -> Map.entry(n, n.getTemplate().getSetup()))
            .flatMap(e -> e.getValue().stream().map(f -> Map.entry(e.getKey(), f)))
            .sorted(Map.Entry.comparingByValue())
            .collect(Collectors.toList());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    while (!stages.isEmpty()) {
      int currentOrder = stages.get(0).getValue().getOrder();

      List<Future<Void>> currentStages = new ArrayList<>();
      while (!stages.isEmpty() && stages.get(0).getValue().getOrder() == currentOrder) {
        Map.Entry<Node, Template.SetupStage> stage = stages.remove(0);

        Future<Void> future =
            executor.submit(
                () -> {
                  stage.getValue().getFoo().setup(env, stage.getKey());
                  return null;
                });

        currentStages.add(future);
      }

      for (Future<Void> f : currentStages) {
        try {
          f.get();
        } catch (InterruptedException e) {
          throw new IOException(e);
        } catch (ExecutionException e) {
          if (e.getCause() instanceof IOException) {
            throw (IOException) e.getCause();
          }
          throw new IOException(e);
        }
      }
    }

    return env;
  }

  private void checkTemplate(Template template) throws ImpossibleBlueprintException {
    if (!config.templateMap.containsKey(template.dist)) {
      throw new ImpossibleBlueprintException(
          this, String.format("Proxmox missing template id for distribution `%s`", template.dist));
    }

    if (template.getInterfaces() == 0) {
      throw new ImpossibleBlueprintException(
          this,
          String.format(
              "Proxmox requires at least one interface for management (kvm not implemented)"));
    }
  }

  private void checkBridgeRequest(BridgeRequest bridgeRequest) throws ImpossibleBlueprintException {
    throw new ImpossibleBlueprintException(this, "Proxmox cannot build bridges (not implemented)");
  }

  private Machine buildMachine(Template template) throws IOException {
    Integer toClone = config.templateMap.get(template.dist);
    int newId = findVmId();

    String cloneJob =
        api.node(auth.node)
            .qemu(toClone)
            .clone(
                new Qemu.Clone(newId)
                    .setName(template.getName())
                    .setDescription("created by virtualtests"));

    awaitTask(cloneJob);

    QemuConfig.SyncUpdate newConfig =
        new QemuConfig.SyncUpdate()
            .setSockets(1)
            .setCores(template.getCoreCount())
            .setMemory(template.getMemoryMb())
            .setCiuser("virtualtests")
            .setNet(new HashMap<>())
            .setIpconfig(new HashMap<>());

    // Set up first interface as management (immutable)
    if (template.getInterfaces() > 0) {
      newConfig.net.put(0, String.format("model=virtio,bridge=%s", config.managementBridge));

      BigInteger addedAddress =
          InetAddresses.toBigInteger(config.initialManagementIp)
              .add(BigInteger.valueOf(newId - config.initialVmId));

      if (config.initialManagementIp instanceof Inet4Address) {
        Inet4Address managementIp = InetAddresses.fromIPv4BigInteger(addedAddress);
        newConfig.ipconfig.put(
            0, String.format("ip=%s/%d", managementIp.getHostAddress(), config.managementNetmask));
      } else {
        Inet6Address managementIp = InetAddresses.fromIPv6BigInteger(addedAddress);
        newConfig.ipconfig.put(
            0, String.format("ip=%s/%d", managementIp.getHostAddress(), config.managementNetmask));
      }
    }

    // Set up second interface as Internet (mutable)
    if (template.getInterfaces() > 1) {
      if (config.internetBridge != null) {
        newConfig.net.put(1, String.format("model=virtio,bridge=%s", config.internetBridge));
      } else {
        newConfig.net.put(1, "model=virtio");
      }
    }

    // Leave future interfaces empty for manual setup
    for (int i = 2; i < template.getInterfaces(); i++) {
      newConfig.net.put(i, "model=virtio");
    }

    api.node(auth.node).qemu(newId).config().put(newConfig);
    return new Machine(template, newId);
  }

  private int findVmId() throws IOException {
    int initialId = config.initialVmId == null ? 100 : config.initialVmId;

    Set<Integer> occupied =
        Arrays.stream(api.node(auth.node).qemus().get())
            .map(Qemu::getVmid)
            .filter(i -> i >= initialId)
            .collect(Collectors.toSet());

    for (int newId = initialId; true; newId++) {
      if (!occupied.contains(newId)) {
        return newId;
      }
    }
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

  public static class ProxmoxConfig {
    private final Map<Distribution, Integer> templateMap;

    private String managementBridge;
    private int managementNetmask;
    private InetAddress initialManagementIp;

    private Integer initialVmId;

    private String internetBridge;

    public ProxmoxConfig() {
      templateMap = new EnumMap<>(Distribution.class);
    }

    public ProxmoxConfig registerTemplate(Distribution dist, Integer id) {
      templateMap.put(dist, id);
      return this;
    }

    public ProxmoxConfig registerTemplates(Map<Distribution, Integer> map) {
      templateMap.putAll(map);
      return this;
    }

    public ProxmoxConfig setManagementBridge(String managementBridge) {
      this.managementBridge = managementBridge;
      return this;
    }

    public ProxmoxConfig setInitialManagementIp(InetAddress initialManagementIp) {
      this.initialManagementIp = initialManagementIp;
      return this;
    }

    public ProxmoxConfig setInitialVmId(Integer initialVmId) {
      this.initialVmId = initialVmId;
      return this;
    }

    public ProxmoxConfig setInternetBridge(String internetBridge) {
      this.internetBridge = internetBridge;
      return this;
    }

    public ProxmoxConfig setManagementNetmask(int managementNetmask) {
      this.managementNetmask = managementNetmask;
      return this;
    }
  }

  public class Machine extends Node {
    private final int id;

    private Machine(Template template, int id) {
      super(template);
      this.id = id;
    }

    @Override
    public void close() throws IOException {
      api.node(auth.node).qemu(id).delete();
    }

    @Override
    public SshReturn ssh(String command) throws IOException {
      return new SshReturn(0, null, null);
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

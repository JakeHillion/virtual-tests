package uk.co.hillion.jake.virtualtests.providers;

import com.google.common.net.InetAddresses;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import uk.co.hillion.jake.proxmox.Network;
import uk.co.hillion.jake.proxmox.ProxmoxAPI;
import uk.co.hillion.jake.proxmox.Qemu;
import uk.co.hillion.jake.proxmox.QemuConfig;
import uk.co.hillion.jake.proxmox.QemuStatus;
import uk.co.hillion.jake.proxmox.Task;
import uk.co.hillion.jake.virtualtests.structure.Blueprint;
import uk.co.hillion.jake.virtualtests.structure.BridgeRequest;
import uk.co.hillion.jake.virtualtests.structure.Distribution;
import uk.co.hillion.jake.virtualtests.structure.Template;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class Proxmox implements Provider {
  private final long RefreshInterval = 1000L;

  private final ProxmoxAuth auth;
  private final ProxmoxConfig config;
  private final ProxmoxAPI api;

  private final String publicKey;
  private final JSch ssh;

  public Proxmox(ProxmoxAuth auth, ProxmoxConfig config) {
    this(auth, config, true);
  }

  public Proxmox(ProxmoxAuth auth, ProxmoxConfig config, boolean verifySsl) {
    this.auth = auth;
    this.config = config;

    api = new ProxmoxAPI(auth.host, auth.user, auth.tokenName, auth.token, verifySsl);
    ssh = new JSch();

    try {
      KeyPair keypair = KeyPair.genKeyPair(ssh, KeyPair.RSA, 2048);
      publicKey = Base64.getEncoder().encodeToString(keypair.getPublicKeyBlob());

      ByteArrayOutputStream privateKeyStream = new ByteArrayOutputStream();
      keypair.writePrivateKey(privateKeyStream);

      ssh.addIdentity(
          "generated", privateKeyStream.toByteArray(), keypair.getPublicKeyBlob(), null);
    } catch (JSchException e) {
      throw new RuntimeException(e);
    }
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
    machines = Collections.unmodifiableList(machines);

    List<Bridge> bridges = new ArrayList<>(blueprint.getBridges().size());
    for (BridgeRequest request : blueprint.getBridges()) {
      bridges.add(buildBridge(request));
    }
    bridges = Collections.unmodifiableList(bridges);

    Environment env = new Environment(machines, bridges);

    // Setup environment according to blueprint
    try {
      List<Map.Entry<Node, Template.SetupStage>> stages =
          machines.stream()
              .map(n -> Map.entry(n, n.getTemplate().getSetup()))
              .flatMap(e -> e.getValue().stream().map(f -> Map.entry(e.getKey(), f)))
              .sorted(Map.Entry.comparingByValue())
              .collect(Collectors.toList());

      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
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
      } finally {
        executor.shutdownNow();
      }
    } catch (Exception e) {
      env.close();
      throw e;
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
          this, "Proxmox requires at least one interface for management (kvm not implemented)");
    }
  }

  private void checkBridgeRequest(BridgeRequest bridgeRequest)
      throws ImpossibleBlueprintException {}

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

    String sshKeys =
        URLEncoder.encode("ssh-rsa " + publicKey, StandardCharsets.US_ASCII).replace("+", "%20");

    QemuConfig.SyncUpdate newConfig =
        new QemuConfig.SyncUpdate()
            .setSockets(1)
            .setCores(template.getCoreCount())
            .setMemory(template.getMemoryMb())
            .setCiuser("root")
            .setNet(new HashMap<>())
            .setIpconfig(new HashMap<>())
            .setSshkeys(sshKeys);

    // Set up first interface as management (immutable)
    if (template.getInterfaces() > 0) {
      newConfig.net.put(0, String.format("model=virtio,bridge=%s", config.managementBridge));

      InetAddress managementAddress = getManagementAddress(newId);
      newConfig.ipconfig.put(
          0,
          String.format("ip=%s/%d", managementAddress.getHostAddress(), config.managementNetmask));
    }

    // Set up second interface as Internet (mutable)
    if (template.getInterfaces() > 1) {
      if (config.internetBridge != null) {
        newConfig.net.put(1, String.format("model=virtio,bridge=%s", config.internetBridge));
      } else {
        newConfig.net.put(1, "model=virtio");
      }

      newConfig.ipconfig.put(1, "ip=dhcp");
    }

    // Leave future interfaces empty for manual setup
    for (int i = 2; i < template.getInterfaces(); i++) {
      newConfig.net.put(i, "model=virtio");
    }

    api.node(auth.node).qemu(newId).config().put(newConfig);
    return new Machine(template, newId);
  }

  private InetAddress getManagementAddress(int vmId) {
    BigInteger addedAddress =
        InetAddresses.toBigInteger(config.initialManagementIp)
            .add(BigInteger.valueOf(vmId - config.initialVmId));

    if (config.initialManagementIp instanceof Inet4Address) {
      return InetAddresses.fromIPv4BigInteger(addedAddress);
    } else {
      return InetAddresses.fromIPv6BigInteger(addedAddress);
    }
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

  private LinuxBridge buildBridge(BridgeRequest request) throws IOException {
    String newName = "vmbr" + findBridgeId();

    api.node(auth.node)
        .networks()
        .post(
            new Network.Create()
                .setIface(newName)
                .setAutostart(true)
                .setType(Network.Create.Type.BRIDGE)
                .setComments("Created by VirtualTests"));

    // Doing this every time is quite inefficient, but it avoids the networks.get
    // call having to deal with some really weird data next time.
    String task = api.node(auth.node).networks().put();
    awaitTask(task);

    return new LinuxBridge(newName);
  }

  private int findBridgeId() throws IOException {
    Set<Integer> occupied =
        Arrays.stream(api.node(auth.node).networks().get())
            .map(Network::getIface)
            .filter(x -> x.startsWith("vmbr"))
            .map(x -> x.substring(4))
            .map(Integer::parseInt)
            .collect(Collectors.toSet());

    for (int newId = 0; true; newId++) {
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

    private final List<Interface> interfaces = new ArrayList<>();

    private Machine(Template template, int id) {
      super(template);
      this.id = id;

      interfaces.add(
          new Interface.ImmutableInterface(getManagementAddress(), config.managementNetmask));
      if (template.getInterfaces() > 1) {
        interfaces.add(new MutableInterface(1, new LinuxBridge(config.internetBridge), null, null));
      }

      for (int i = 2; i < template.getInterfaces(); i++) {
        interfaces.add(new MutableInterface(i));
      }
    }

    @Override
    public List<Interface> getInterfaces() {
      return Collections.unmodifiableList(interfaces);
    }

    @Override
    public void close() throws IOException {
      QemuStatus status = api.node(auth.node).qemu(id).status().get();
      if (status.getStatus() == QemuStatus.Status.RUNNING) {
        String stopJob = api.node(auth.node).qemu(id).status().stop(new QemuStatus.Stop());
        awaitTask(stopJob);
      }
      String deleteJob = api.node(auth.node).qemu(id).delete();
      awaitTask(deleteJob);
    }

    @Override
    public void start() throws IOException {
      String startJob = api.node(auth.node).qemu(id).status().start(new QemuStatus.Start());
      awaitTask(startJob);
    }

    @Override
    public void stop() throws IOException {
      String stopJob = api.node(auth.node).qemu(id).status().shutdown(new QemuStatus.Shutdown());
      awaitTask(stopJob);
    }

    @Override
    public SSHResult ssh(String command, long connectionTimeoutMillis) throws IOException {
      try {
        Session session = ssh.getSession("root", getManagementAddress().getHostAddress());
        session.setConfig("StrictHostKeyChecking", "no");

        try (SshUtils.ManagedSession s =
            SshUtils.getManagedSession(session, connectionTimeoutMillis)) {
          try (SshUtils.ManagedChannel<ChannelExec> mc = s.getChannelExec()) {
            ChannelExec c = mc.getChannel();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            c.setOutputStream(stdout);
            c.setErrStream(stderr);

            c.setCommand(command);

            c.connect();

            while (c.isConnected()) {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
            }

            return new SSHResult(c.getExitStatus(), stdout.toByteArray(), stderr.toByteArray());
          }
        }

      } catch (JSchException e) {
        throw new IOException(e);
      }
    }

    private InetAddress getManagementAddress() {
      return Proxmox.this.getManagementAddress(id);
    }

    public class MutableInterface extends Interface {

      private final int id;

      private LinuxBridge bridge;
      private Integer rate;
      private boolean enabled = true;

      private Inet4Address addressv4;
      private int netmaskv4 = 24;
      private Inet6Address addressv6;
      private int netmaskv6 = 64;

      private MutableInterface(int id) {
        this.id = id;
      }

      private MutableInterface(int id, LinuxBridge bridge, Inet4Address addressv4, Inet6Address addressv6) {
        this.id = id;

        this.bridge = bridge;

        this.addressv4 = addressv4;
        this.addressv6 = addressv6;
      }

      @Override
      public void setRate(Integer rate) throws IOException {
        this.rate = rate;
        updateNetConfig();
      }

      @Override
      public void setEnabled(boolean enabled) throws IOException {
        this.enabled = enabled;
        updateNetConfig();
      }

      @Override
      public void setBridge(Bridge bridge) throws IOException {
        if (!(bridge instanceof LinuxBridge)) {
          throw new UnsupportedOperationException("requires proxmox bridge");
        }
        LinuxBridge linuxBridge = (LinuxBridge) bridge;
        if (linuxBridge.getParent() != Proxmox.this) {
          throw new UnsupportedOperationException("requires proxmox bridge from this proxmox instance");
        }

        this.bridge = linuxBridge;
        updateNetConfig();
      }

      private void updateNetConfig() throws IOException {
        StringBuilder netConfig = new StringBuilder(String.format("model=virtio,bridge=%s", bridge.bridge));
        if (!enabled) {
          netConfig.append(",link_down=1");
        }
        if (rate != null) {
          netConfig.append(",rate=").append(rate);
        }


        QemuConfig.SyncUpdate newConfig = new QemuConfig.SyncUpdate();
        newConfig.ipconfig.put(id, netConfig.toString());
        api.node(auth.node).qemu(Machine.this.id).config().put(newConfig);
      }

      @Override
      public void setAddress(Inet4Address address) throws IOException {
        addressv4 = address;
        updateIpConfig();
      }

      @Override
      public void setNetmaskV4(int netmask) throws IOException {
        this.netmaskv4 = netmask;
        updateIpConfig();
      }

      @Override
      public Inet4Address getAddressV4() {
        return addressv4;
      }

      @Override
      public void setAddress(Inet6Address address) throws IOException {
        addressv6 = address;
        updateIpConfig();
      }

      @Override
      public void setNetmaskV6(int netmask) throws IOException {
        this.netmaskv6 = netmask;
        updateIpConfig();
      }

      @Override
      public Inet6Address getAddressV6() {
        return addressv6;
      }

      private void updateIpConfig() throws IOException {
        StringBuilder ipConfig = new StringBuilder();
        if (addressv4 != null) {
          ipConfig.append("ip=").append(addressv4).append('/').append(netmaskv4);
        }
        if (addressv6 != null) {
          ipConfig.append("ip6=").append(addressv6).append('/').append(netmaskv6);
        }

        QemuConfig.SyncUpdate newConfig = new QemuConfig.SyncUpdate();
        newConfig.ipconfig.put(id, ipConfig.toString());
        api.node(auth.node).qemu(Machine.this.id).config().put(newConfig);
      }
    }
  }

  public class LinuxBridge extends Bridge {
    private final String bridge;

    public LinuxBridge(String bridge) {
      this.bridge = bridge;
    }

    @Override
    public void close() throws IOException {
      api.node(auth.node).network(bridge).delete();
    }

    @Override
    public void closeAll() throws IOException {
      api.node(auth.node).networks().put();
    }

    private Proxmox getParent() {
      return Proxmox.this;
    }
  }
}

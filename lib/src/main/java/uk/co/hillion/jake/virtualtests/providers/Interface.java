package uk.co.hillion.jake.virtualtests.providers;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

public abstract class Interface {
  public abstract void setRate(Integer rate) throws IOException;

  public abstract void setEnabled(boolean enabled) throws IOException;

  public abstract void setBridge(Bridge bridge) throws IOException;

  public abstract void setAddress(Inet4Address address) throws IOException;

  public abstract void setNetmaskV4(int netmask) throws IOException;

  public abstract Inet4Address getAddressV4() throws IOException;

  public abstract void setAddress(Inet6Address address) throws IOException;

  public abstract void setNetmaskV6(int netmask) throws IOException;

  public abstract Inet6Address getAddressV6() throws IOException;

  public static class ImmutableInterface extends Interface {

    private final Inet4Address addressv4;
    private final Inet6Address addressv6;

    public ImmutableInterface(InetAddress address, int netmask) {
      if (address instanceof Inet4Address) {
        addressv4 = (Inet4Address) address;
        addressv6 = null;
      } else if (address instanceof Inet6Address) {
        addressv4 = null;
        addressv6 = (Inet6Address) address;
      } else {
        addressv4 = null;
        addressv6 = null;
      }
    }

    public ImmutableInterface(Inet4Address addressv4, Inet6Address addressv6) {
      this.addressv4 = addressv4;
      this.addressv6 = addressv6;
    }

    @Override
    public Inet4Address getAddressV4() throws IOException {
      return addressv4;
    }

    @Override
    public Inet6Address getAddressV6() throws IOException {
      return addressv6;
    }

    @Override
    public void setRate(Integer rate) throws IOException {
      throw new UnsupportedOperationException("immutable interface");
    }

    @Override
    public void setEnabled(boolean enabled) throws IOException {
      throw new UnsupportedOperationException("immutable interface");
    }

    @Override
    public void setBridge(Bridge bridge) throws IOException {
      throw new UnsupportedOperationException("immutable interface");
    }

    @Override
    public void setAddress(Inet4Address address) throws IOException {
      throw new UnsupportedOperationException("immutable interface");
    }

    @Override
    public void setNetmaskV4(int netmask) throws IOException {
      throw new UnsupportedOperationException("immutable interface");
    }

    @Override
    public void setAddress(Inet6Address address) throws IOException {
      throw new UnsupportedOperationException("immutable interface");
    }

    @Override
    public void setNetmaskV6(int netmask) throws IOException {
      throw new UnsupportedOperationException("immutable interface");
    }
  }
}

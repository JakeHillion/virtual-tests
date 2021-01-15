package uk.co.hillion.jake.virtualtests.providers;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;

public class SshUtils {
  public static ManagedSession getManagedSession(Session session) throws IOException {
    return getManagedSession(session, 0);
  }

  public static ManagedSession getManagedSession(Session session, long timeoutMillis)
      throws IOException {
    Exception e;
    long startTime = System.currentTimeMillis();
    do {
      try {
        session.connect();
        return new ManagedSession(session);
      } catch (JSchException ex) {
        e = ex;
      }
    } while (System.currentTimeMillis() < startTime + timeoutMillis);

    throw new IOException(e);
  }

  public static class ManagedSession implements AutoCloseable {
    private final Session session;

    private ManagedSession(Session session) throws IOException {
      if (!session.isConnected()) {
        throw new IOException("managedsession handed disconnected session");
      }

      this.session = session;
    }

    public ManagedChannel<ChannelExec> getChannelExec() throws IOException {
      try {
        return new ManagedChannel<>((ChannelExec) session.openChannel("exec"));
      } catch (JSchException e) {
        throw new IOException(e);
      }
    }

    @Override
    public void close() {
      session.disconnect();
    }
  }

  public static class ManagedChannel<T extends Channel> implements AutoCloseable {
    private final T channel;

    public ManagedChannel(T channel) {
      this.channel = channel;
    }

    public T getChannel() {
      return channel;
    }

    @Override
    public void close() {
      if (channel.isConnected()) {
        channel.disconnect();
      }
    }
  }
}

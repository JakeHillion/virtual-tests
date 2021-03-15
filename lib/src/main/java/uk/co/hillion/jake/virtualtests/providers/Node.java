package uk.co.hillion.jake.virtualtests.providers;

import uk.co.hillion.jake.virtualtests.structure.Template;

import java.io.IOException;
import java.util.List;

public abstract class Node implements AutoCloseable {
  private final Template template;

  protected Node(Template template) {
    this.template = template;
  }

  public abstract List<Interface> getInterfaces();

  @Override
  public abstract void close() throws IOException;

  public void closeAll() throws IOException {}

  public abstract void start() throws IOException;

  public abstract void stop() throws IOException;

  public abstract SSHResult ssh(String command, long connectionTimeoutMillis) throws IOException;

  public SSHResult ssh(String command) throws IOException {
    return ssh(command, 30000);
  }

  public SSHResult mustSsh(String command) throws IOException {
    return mustSsh(command, 30000);
  }

  public SSHResult mustSsh(String command, long connectionTimeoutMillis) throws IOException {
    SSHResult result = ssh(command, connectionTimeoutMillis);
    if (result.getReturnCode() != 0) {
      throw new SSHException(result);
    }
    return result;
  }

  public Template getTemplate() {
    return template;
  }

  public static class SSHResult {
    private final int returnCode;
    private final byte[] stdout;
    private final byte[] stderr;

    protected SSHResult(int returnCode, byte[] stdout, byte[] stderr) {
      this.returnCode = returnCode;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public int getReturnCode() {
      return returnCode;
    }

    public byte[] getStdout() {
      return stdout;
    }

    public byte[] getStderr() {
      return stderr;
    }
  }

  public static class SSHException extends IOException {
    private final SSHResult result;

    private SSHException(SSHResult result) {
      super();
      this.result = result;
    }

    public SSHResult getResult() {
      return result;
    }
  }
}

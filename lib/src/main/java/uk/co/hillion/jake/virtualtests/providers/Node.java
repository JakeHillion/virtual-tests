package uk.co.hillion.jake.virtualtests.providers;

import uk.co.hillion.jake.virtualtests.structure.Template;

import java.io.IOException;

public abstract class Node implements AutoCloseable {
  private final Template template;

  protected Node(Template template) {
    this.template = template;
  }

  @Override
  public abstract void close() throws IOException;

  public abstract void start() throws IOException;

  public abstract void stop() throws IOException;

  public abstract SshReturn ssh(String command, long connectionTimeoutMillis) throws IOException;

  public SshReturn ssh(String command) throws IOException {
    return ssh(command, 30000);
  }

  public Template getTemplate() {
    return template;
  }

  public static class SshReturn {
    private final int returnCode;
    private final byte[] stdout;
    private final byte[] stderr;

    protected SshReturn(int returnCode, byte[] stdout, byte[] stderr) {
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
}

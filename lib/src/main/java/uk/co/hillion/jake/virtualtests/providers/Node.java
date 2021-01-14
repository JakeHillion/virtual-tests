package uk.co.hillion.jake.virtualtests.providers;

import uk.co.hillion.jake.virtualtests.structure.Template;

import java.io.IOException;
import java.io.Reader;

public abstract class Node implements AutoCloseable {
  private final Template template;

  protected Node(Template template) {
    this.template = template;
  }

  @Override
  public abstract void close() throws IOException;

  public abstract SshReturn ssh(String command) throws IOException;

  public Template getTemplate() {
    return template;
  }

  public static class SshReturn {
    private final int returnCode;
    private final Reader stdout;
    private final Reader stderr;

    protected SshReturn(int returnCode, Reader stdout, Reader stderr) {
      this.returnCode = returnCode;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public int getReturnCode() {
      return returnCode;
    }

    public Reader getStdout() {
      return stdout;
    }

    public Reader getStderr() {
      return stderr;
    }
  }
}

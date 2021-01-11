package uk.co.hillion.jake.virtualtests.providers;

import java.io.IOException;

public abstract class Node implements AutoCloseable {
  @Override
  public abstract void close() throws IOException;
}
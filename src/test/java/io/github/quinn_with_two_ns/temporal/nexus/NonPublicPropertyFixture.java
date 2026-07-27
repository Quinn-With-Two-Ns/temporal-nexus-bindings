package io.github.quinn_with_two_ns.temporal.nexus;

/**
 * Supplies an instance whose property getter is declared on a non-public class, mirroring payloads
 * deserialized into hidden implementations of public interfaces. Lives outside the {@code internal}
 * package so reflective access checks from the expression runtime actually apply.
 */
public final class NonPublicPropertyFixture {
  private NonPublicPropertyFixture() {}

  /** A public view of the hidden implementation. */
  public interface Named {
    /** Returns the fixture name. */
    String getName();
  }

  /** Returns an instance whose runtime class is not public. */
  public static Named hidden() {
    return new Hidden();
  }

  private static final class Hidden implements Named {
    @Override
    public String getName() {
      return "hidden";
    }
  }
}

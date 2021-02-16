package uk.co.hillion.jake.virtualtests.providers;

public class ImpossibleBlueprintException extends Exception {
  public ImpossibleBlueprintException(Provider provider, String reason) {
    super(
        String.format(
            "provider `%s` unable to process blueprint: %s",
            provider.getClass().getSimpleName(), reason));
  }
}

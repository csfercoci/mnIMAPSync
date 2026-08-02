package com.marcnuri.mnimapsync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class HostDefinitionTest {

  @Test
  void setRetries_negativeValue_shouldThrowException() {
    final HostDefinition hostDefinition = new HostDefinition();

    assertThrows(IllegalArgumentException.class, () -> hostDefinition.setRetries(-1));
  }

  @Test
  void setConnectTimeout_zero_shouldThrowException() {
    final HostDefinition hostDefinition = new HostDefinition();

    assertThrows(IllegalArgumentException.class, () -> hostDefinition.setConnectTimeout(0));
  }

  @Test
  void setReadTimeout_zero_shouldThrowException() {
    final HostDefinition hostDefinition = new HostDefinition();

    assertThrows(IllegalArgumentException.class, () -> hostDefinition.setReadTimeout(0));
  }

  @Test
  void setWatchInterval_zero_shouldThrowException() {
    final SyncOptions syncOptions = new SyncOptions();

    assertThrows(IllegalArgumentException.class, () -> syncOptions.setWatchInterval(0));
  }
}

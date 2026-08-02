package com.marcnuri.mnimapsync.index;

import jakarta.mail.Message;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.doReturn;

class MessageStateTest {

  @Test
  void hasSameContent_matchingBodies_shouldReturnTrue() throws Exception {
    final Message indexedMessage = Mockito.mock(Message.class);
    doReturn(stream("body")).when(indexedMessage).getInputStream();
    final MessageState state = MessageState.from(indexedMessage, 42L);
    final Message sourceMessage = Mockito.mock(Message.class);
    doReturn(stream("body")).when(sourceMessage).getInputStream();

    assertThat(state.hasSameContent(sourceMessage), equalTo(true));
  }

  @Test
  void hasSameContent_changedBodies_shouldReturnFalse() throws Exception {
    final Message indexedMessage = Mockito.mock(Message.class);
    doReturn(stream("old body")).when(indexedMessage).getInputStream();
    final MessageState state = MessageState.from(indexedMessage, 42L);
    final Message sourceMessage = Mockito.mock(Message.class);
    doReturn(stream("new body")).when(sourceMessage).getInputStream();

    assertThat(state.hasSameContent(sourceMessage), equalTo(false));
  }

  private static ByteArrayInputStream stream(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}

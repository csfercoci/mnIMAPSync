/*
 * MessageCopierTest.java
 *
 * Created on 2019-08-17, 8:24
 *
 * Copyright 2019 Marc Nuri San Felix
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.marcnuri.mnimapsync.store;

import com.marcnuri.mnimapsync.index.Index;
import com.marcnuri.mnimapsync.index.MessageId;
import com.marcnuri.mnimapsync.index.MessageState;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Created by Marc Nuri <marc@marcnuri.com> on 2019-08-17.
 */
public class MessageCopierTest {

  private IMAPFolder imapFolder;
  private IMAPStore imapStore;
  private Index sourceIndex;
  private Index targetIndex;
  private StoreCopier storeCopier;

  @BeforeEach
  void setUp() throws Exception {
    imapFolder = Mockito.mock(IMAPFolder.class);
    doReturn('.').when(imapFolder).getSeparator();
    imapStore = Mockito.mock(IMAPStore.class);
    doReturn(imapFolder).when(imapStore).getFolder(anyString());
    doReturn(imapFolder).when(imapStore).getDefaultFolder();
    sourceIndex = Mockito.spy(new Index());
    targetIndex = Mockito.spy(new Index());
    storeCopier = Mockito.spy(new StoreCopier(imapStore, sourceIndex, imapStore, targetIndex, 1));
  }

  @AfterEach
  void tearDown() {
    storeCopier = null;
    targetIndex = null;
    sourceIndex = null;
    imapStore = null;
    imapFolder = null;
  }

  @Test
  void run_emptyFolder_shouldOnlyUpdateIndexes() throws Exception {
    // Given
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 0, 100, new HashSet<>());
    doReturn(new Message[0]).when(imapFolder).getMessages(eq(0), eq(100));
    // When
    messageCopier.run();
    // Then
    verify(storeCopier, times(1)).updatedMessagesCopiedCount(eq(0L));
    verify(storeCopier, times(1)).updateMessagesSkippedCount(eq(0L));
    verify(sourceIndex, times(1)).updatedIndexedMessageCount(eq(0L));
  }

  @Test
  void run_folderWithAlreadyCopiedMessages_shouldOnlyUpdateIndexes() throws Exception {
    // Given
    final Set<MessageId> copiedMessages = new HashSet<>();
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 0, 100, copiedMessages);
    final IMAPMessage message = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"1337"}).when(message).getHeader("Message-ID");
    copiedMessages.add(new MessageId(message));
    doReturn(new Message[]{message}).when(imapFolder).getMessages(eq(0), eq(100));
    // When
    messageCopier.run();
    // Then
    verify(storeCopier, times(1)).updatedMessagesCopiedCount(eq(0L));
    verify(storeCopier, times(1)).updateMessagesSkippedCount(eq(1L));
    verify(sourceIndex, times(1)).updatedIndexedMessageCount(eq(1L));
    assertThat(storeCopier.getMessagesSkippedCount(), equalTo(1L));
  }

  @Test
  void run_folderWithCopiedAndNonCopiedMessages_shouldUpdateIndexesAndCopy() throws Exception {
    // Given
    final Set<MessageId> copiedMessages = new HashSet<>();
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 0, 100, copiedMessages);
    final IMAPMessage copiedMessage = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"1337"}).when(copiedMessage).getHeader("Message-ID");
    copiedMessages.add(new MessageId(copiedMessage));
    final IMAPMessage newMessage = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"313373"}).when(newMessage).getHeader("Message-ID");
    doReturn(new Message[]{copiedMessage, newMessage}).when(imapFolder).getMessages(eq(0), eq(100));
    // When
    messageCopier.run();
    // Then
    verify(imapFolder, times(1)).appendMessages(ArgumentMatchers.any());
    verify(storeCopier, times(1)).updatedMessagesCopiedCount(eq(1L));
    verify(storeCopier, times(1)).updateMessagesSkippedCount(eq(1L));
    verify(sourceIndex, times(1)).updatedIndexedMessageCount(eq(2L));
    assertThat(storeCopier.getMessagesSkippedCount(), equalTo(1L));
    assertThat(storeCopier.getMessagesCopiedCount(), equalTo(1L));
  }

  @Test
  void run_folderThrowsException_shouldOnlyUpdateIndexes() throws Exception {
    // Given
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 0, 100, new HashSet<>());
    doThrow(new MessagingException()).when(imapFolder).open(anyInt());
    // When
    messageCopier.run();
    // Then
    assertThat(storeCopier.getCopyExceptions(), hasSize(1));
    verify(storeCopier, times(1)).updatedMessagesCopiedCount(eq(0L));
    verify(storeCopier, times(1)).updateMessagesSkippedCount(eq(0L));
    verify(sourceIndex, times(1)).updatedIndexedMessageCount(eq(0L));
  }

  @Test
  void run_messageWithoutStableId_shouldMakeFolderUnsafeForDeletion() throws Exception {
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 1, 1, new HashSet<>());
    final IMAPMessage message = Mockito.mock(IMAPMessage.class);
    doReturn(new Message[]{message}).when(imapFolder).getMessages(eq(1), eq(1));

    messageCopier.run();

    assertThat(sourceIndex.isFolderUnsafeForDeletion("Source Folder"), equalTo(true));
  }

  @Test
  void run_existingMessageWithMatchingContent_shouldSynchronizeFlags() throws Exception {
    final IMAPMessage sourceMessage = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"stable-id"}).when(sourceMessage).getHeader("Message-ID");
    doReturn(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)))
        .when(sourceMessage).getInputStream();
    doReturn(true).when(sourceMessage).isSet(Flags.Flag.SEEN);
    final Message targetMessage = Mockito.mock(Message.class);
    doReturn(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)))
        .when(targetMessage).getInputStream();
    final MessageId messageId = new MessageId(sourceMessage);
    targetIndex.putMessageState("Target Folder", messageId, MessageState.from(targetMessage, 42L));
    final Set<MessageId> targetMessages = new HashSet<>();
    targetMessages.add(messageId);
    doReturn(new Message[]{sourceMessage}).when(imapFolder).getMessages(eq(1), eq(1));
    doReturn(targetMessage).when(imapFolder).getMessageByUID(42L);
    doReturn(new Flags(Flags.Flag.SEEN)).when(imapFolder).getPermanentFlags();
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 1, 1, targetMessages);

    messageCopier.run();

    verify(targetMessage).setFlag(Flags.Flag.SEEN, true);
    verify(imapFolder, never()).appendMessages(any(Message[].class));
  }

  @Test
  void run_existingMessageWithChangedContent_shouldAppendThenDeleteOldMessage() throws Exception {
    final IMAPMessage sourceMessage = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"stable-id"}).when(sourceMessage).getHeader("Message-ID");
    doReturn(new ByteArrayInputStream("new body".getBytes(StandardCharsets.UTF_8)))
        .when(sourceMessage).getInputStream();
    final Message targetMessage = Mockito.mock(Message.class);
    doReturn(new ByteArrayInputStream("old body".getBytes(StandardCharsets.UTF_8)))
        .when(targetMessage).getInputStream();
    final MessageId messageId = new MessageId(sourceMessage);
    targetIndex.putMessageState("Target Folder", messageId, MessageState.from(targetMessage, 42L));
    final Set<MessageId> targetMessages = new HashSet<>();
    targetMessages.add(messageId);
    doReturn(new Message[]{sourceMessage}).when(imapFolder).getMessages(eq(1), eq(1));
    doReturn(targetMessage).when(imapFolder).getMessageByUID(42L);
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 1, 1, targetMessages);

    messageCopier.run();

    verify(imapFolder).appendMessages(new Message[]{sourceMessage});
    verify(targetMessage).setFlag(Flags.Flag.DELETED, true);
  }

  @Test
  void run_existingMessageWithDynamicUserFlags_shouldSynchronizeSourceAndTargetFlags()
      throws Exception {
    final IMAPMessage sourceMessage = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"stable-id"}).when(sourceMessage).getHeader("Message-ID");
    doReturn(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)))
        .when(sourceMessage).getInputStream();
    doReturn(new Flags("source-label")).when(sourceMessage).getFlags();
    final Message targetMessage = Mockito.mock(Message.class);
    doReturn(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)))
        .when(targetMessage).getInputStream();
    doReturn(new Flags("target-label")).when(targetMessage).getFlags();
    final MessageId messageId = new MessageId(sourceMessage);
    targetIndex.putMessageState("Target Folder", messageId, MessageState.from(targetMessage, 42L));
    final Set<MessageId> targetMessages = new HashSet<>();
    targetMessages.add(messageId);
    doReturn(new Message[]{sourceMessage}).when(imapFolder).getMessages(eq(1), eq(1));
    doReturn(targetMessage).when(imapFolder).getMessageByUID(42L);
    doReturn(new Flags(Flags.Flag.USER)).when(imapFolder).getPermanentFlags();
    final MessageCopier messageCopier = new MessageCopier(
        storeCopier, "Source Folder", "Target Folder", 1, 1, targetMessages);

    messageCopier.run();

    verify(targetMessage).setFlags(new Flags("source-label"), true);
    verify(targetMessage).setFlags(new Flags("target-label"), false);
    verify(targetMessage, never()).setFlag(Flags.Flag.USER, false);
  }
}

/*
 * MessageDeleterTest.java
 *
 * Created on 2019-08-18, 9:23
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
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Created by Marc Nuri <marc@marcnuri.com> on 2019-08-18.
 */
class MessageDeleterTest {

  private IMAPFolder imapFolder;
  private IMAPStore imapStore;
  private Index sourceIndex;
  private Index targetIndex;
  private StoreDeleter storeDeleter;

  @BeforeEach
  void setUp() throws Exception {
    imapFolder = Mockito.mock(IMAPFolder.class);
    imapStore = Mockito.mock(IMAPStore.class);
    doReturn(imapFolder).when(imapStore).getFolder(anyString());
    doReturn(imapFolder).when(imapStore).getDefaultFolder();
    sourceIndex = Mockito.spy(new Index());
    sourceIndex.setFolderSeparator(".");
    targetIndex = Mockito.spy(new Index());
    targetIndex.setFolderSeparator("_");
    storeDeleter = Mockito.spy(new StoreDeleter(sourceIndex, targetIndex, imapStore, 1));
  }

  @AfterEach
  void tearDown() {
    storeDeleter = null;
    sourceIndex = null;
    targetIndex = null;
    imapStore = null;
  }

  @Test
  void run_emptyFolder_shouldOnlyUpdateIndexes() throws Exception {
    // Given
    final MessageDeleter messageDeleter = new MessageDeleter(
        storeDeleter, "Target Folder",
        0, 100, true, new HashSet<>());
    doReturn(new Message[0]).when(imapFolder).getMessages(eq(0), eq(100));
    // When
    messageDeleter.run();
    // Then
    verify(storeDeleter, times(1)).updatedMessagesDeletedCount(eq(0L));
    verify(storeDeleter, times(1)).updateMessagesSkippedCount(eq(0L));
  }

  @Test
  void run_folderWithNonDeletableMessages_shouldOnlyUpdateIndexes() throws Exception {
    // Given
    final Set<MessageId> sourceFolderMessages = new HashSet<>();
    final MessageDeleter messageDeleter = new MessageDeleter(
        storeDeleter, "Target Folder",
        0, 100, true, sourceFolderMessages);
    final IMAPMessage message = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"1337"}).when(message).getHeader("Message-Id");
    sourceFolderMessages.add(new MessageId(message));
    doReturn(new Message[]{message}).when(imapFolder).getMessages(eq(0), eq(100));
    // When
    messageDeleter.run();
    // Then
    verify(imapFolder, times(1)).close(eq(true));
    verify(storeDeleter, times(1)).updatedMessagesDeletedCount(eq(0L));
    verify(storeDeleter, times(1)).updateMessagesSkippedCount(eq(1L));
    assertThat(storeDeleter.getMessagesSkippedCount(), equalTo(1L));
  }

  @Test
  void run_folderWithAllMessageTypes_shouldOnlyUpdateIndexes() throws Exception {
    // Given
    final Set<MessageId> sourceFolderMessages = new HashSet<>();
    final MessageDeleter messageDeleter = new MessageDeleter(
        storeDeleter, "Target Folder",
        0, 100, true, sourceFolderMessages);
    final IMAPMessage existingSourceMessage = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"1337"}).when(existingSourceMessage).getHeader("Message-Id");
    sourceFolderMessages.add(new MessageId(existingSourceMessage));
    final IMAPMessage deletableMessage = Mockito.mock(IMAPMessage.class);
    doReturn(new String[]{"313373"}).when(deletableMessage).getHeader("Message-Id");
    doReturn(new Message[]{existingSourceMessage, deletableMessage}).when(imapFolder).getMessages(eq(0), eq(100));
    // When
    messageDeleter.run();
    // Then
    verify(deletableMessage, times(1)).setFlag(eq(Flags.Flag.DELETED), eq(true));
    verify(imapFolder, times(1)).close(eq(true));
    verify(storeDeleter, times(1)).updatedMessagesDeletedCount(eq(1L));
    verify(storeDeleter, times(1)).updateMessagesSkippedCount(eq(1L));
    assertThat(storeDeleter.getMessagesSkippedCount(), equalTo(1L));
  }
}
